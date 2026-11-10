package amazon.microservices.core.review.services;

import amazon.api.discovery.CustomerReview;
import amazon.api.discovery.CustomerReviewPage;
import amazon.api.discovery.VerifiedCustomerReviewSubmission;
import amazon.api.discovery.RatingSummary;
import amazon.microservices.core.review.persistence.CustomerReviewEntity;
import amazon.microservices.core.review.persistence.CustomerReviewRepository;
import amazon.util.exceptions.InvalidInputException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MySQL-backed customer ratings, separate from legacy imported reviews.
 * JWT owner identity is derived by the authenticated composite service.
 * Blocking JPA work is always scheduled off the WebFlux event-loop.
 */
@RestController
@RequestMapping("/internal/customer-reviews")
public class CustomerReviewController {
    private final CustomerReviewRepository repository;
    private final Scheduler scheduler;

    public CustomerReviewController(CustomerReviewRepository repository, Scheduler jdbcScheduler) {
        this.repository = repository;
        this.scheduler = jdbcScheduler;
    }

    @PutMapping(value = "/{productId}/owner/{ownerKey}", consumes = "application/json")
    public Mono<CustomerReview> put(@PathVariable int productId, @PathVariable String ownerKey,
                                    @RequestBody VerifiedCustomerReviewSubmission request) {
        validateProduct(productId);
        validateOwner(ownerKey);
        validateRequest(request);
        return Mono.fromCallable(() -> {
            CustomerReviewEntity entity = repository.findByProductIdAndOwnerKey(productId, ownerKey)
                    .orElseGet(() -> new CustomerReviewEntity(
                            productId, ownerKey, request.rating(), request.title(), request.content()));
            // Once a mock purchase is proven, later edits cannot erase that provenance.
            String normalizedTitle = request.title().trim();
            String normalizedContent = request.content().trim();
            if (entity.getId() != null
                    && (entity.isMockPurchaseConfirmed() || !request.verifiedMockPurchase())
                    && entity.getRating() == request.rating()
                    && entity.getTitle().equals(normalizedTitle)
                    && entity.getContent().equals(normalizedContent))
                return publicReview(entity);
            entity.setMockPurchaseConfirmed(entity.isMockPurchaseConfirmed() || request.verifiedMockPurchase());
            entity.setRating(request.rating());
            entity.setTitle(normalizedTitle);
            entity.setContent(normalizedContent);
            entity.setUpdatedAt(java.time.Instant.now());
            try {
                return publicReview(repository.saveAndFlush(entity));
            } catch (DataIntegrityViolationException ex) {
                throw new InvalidInputException("Concurrent review creation; retry request");
            } catch (OptimisticLockingFailureException ex) {
                throw new InvalidInputException("Concurrent review update; retry request");
            }
        }).subscribeOn(scheduler);
    }

    @DeleteMapping("/{productId}/owner/{ownerKey}")
    public Mono<Void> delete(@PathVariable int productId, @PathVariable String ownerKey) {
        validateProduct(productId);
        validateOwner(ownerKey);
        return Mono.fromRunnable(() ->
                repository.findByProductIdAndOwnerKey(productId, ownerKey)
                        .ifPresent(repository::delete))
                .subscribeOn(scheduler).then();
    }

    @GetMapping("/{productId}")
    public Mono<CustomerReviewPage> list(@PathVariable int productId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(defaultValue = "newest") String sort) {
        validateProduct(productId);
        validatePage(page, size);
        if (!"newest".equals(sort) && !"oldest".equals(sort) && !"highest".equals(sort)
                && !"lowest".equals(sort))
            throw new InvalidInputException("sort must be newest, oldest, highest or lowest");
        Sort ordering = switch (sort) {
            case "oldest" -> Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by("id"));
            case "highest" -> Sort.by(Sort.Direction.DESC, "rating")
                    .and(Sort.by(Sort.Direction.DESC, "createdAt")).and(Sort.by("id"));
            case "lowest" -> Sort.by(Sort.Direction.ASC, "rating")
                    .and(Sort.by(Sort.Direction.DESC, "createdAt")).and(Sort.by("id"));
            default -> Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"));
        };
        return Mono.fromCallable(() -> {
            Page<CustomerReviewEntity> results = repository.findByProductIdAndVisibility(
                    productId, "VISIBLE", PageRequest.of(page, size, ordering));
            return new CustomerReviewPage(results.getContent().stream().map(this::publicReview).toList(),
                    page, size, results.getTotalElements(), results.getTotalPages());
        }).subscribeOn(scheduler);
    }

    @GetMapping("/{productId}/ratings")
    public Mono<RatingSummary> ratings(@PathVariable int productId) {
        validateProduct(productId);
        return Mono.fromCallable(() -> {
            Map<Integer, Long> counts = new LinkedHashMap<>();
            for (int i = 1; i <= 5; i++) counts.put(i, 0L);
            long total = 0;
            long weighted = 0;
            for (CustomerReviewRepository.RatingBucket bucket : repository.ratingCounts(productId)) {
                int rating = bucket.getRating();
                long amount = bucket.getReviewCount();
                counts.put(rating, amount);
                total += amount;
                weighted += (long) rating * amount;
            }
            BigDecimal average = total == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(weighted).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            return new RatingSummary(productId, total, average, counts);
        }).subscribeOn(scheduler);
    }

    private CustomerReview publicReview(CustomerReviewEntity entity) {
        return new CustomerReview(entity.getId(), entity.getProductId(), entity.getRating(),
                entity.getTitle(), entity.getContent(), entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.isMockPurchaseConfirmed());
    }

    private void validateRequest(VerifiedCustomerReviewSubmission r) {
        if (r == null || r.rating() < 1 || r.rating() > 5
                || r.title() == null || r.title().isBlank() || r.title().length() > 120
                || r.content() == null || r.content().isBlank() || r.content().length() > 2000)
            throw new InvalidInputException("rating 1-5, title 1-120 and content 1-2000 required");
    }

    private void validateOwner(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}"))
            throw new InvalidInputException("Invalid reviewer identity");
    }

    private void validateProduct(int id) {
        if (id < 1) throw new InvalidInputException("Invalid productId");
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new InvalidInputException("page must be >= 0 and size must be 1-100");
    }
}
