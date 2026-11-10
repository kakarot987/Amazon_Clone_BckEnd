package amazon.microservices.core.review.services;

import amazon.api.discovery.ModerationReviewDetails;
import amazon.api.discovery.ReviewModerationRequest;
import amazon.api.discovery.ReviewReportPage;
import amazon.api.discovery.ReviewReportReceipt;
import amazon.api.discovery.ReviewReportRequest;
import amazon.microservices.core.review.persistence.CustomerReviewEntity;
import amazon.microservices.core.review.persistence.CustomerReviewReportEntity;
import amazon.microservices.core.review.persistence.CustomerReviewReportRepository;
import amazon.microservices.core.review.persistence.CustomerReviewRepository;
import amazon.microservices.core.review.persistence.ReviewModerationEventEntity;
import amazon.microservices.core.review.persistence.ReviewModerationEventRepository;
import amazon.util.exceptions.InvalidInputException;
import amazon.util.exceptions.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.List;
import java.util.Set;

/** Private JPA moderation; composite must protect operator routes with JWT scopes. */
@RestController
@RequestMapping("/internal/review-moderation")
public class ReviewModerationController {
    private static final Set<String> REASONS = Set.of("SPAM", "ABUSE", "OFF_TOPIC", "MISLEADING");
    private final CustomerReviewRepository reviews;
    private final CustomerReviewReportRepository reports;
    private final ReviewModerationEventRepository events;
    private final TransactionTemplate tx;
    private final Scheduler scheduler;

    public ReviewModerationController(CustomerReviewRepository reviews,
                                      CustomerReviewReportRepository reports,
                                      ReviewModerationEventRepository events,
                                      org.springframework.transaction.PlatformTransactionManager transactionManager,
                                      Scheduler jdbcScheduler) {
        this.reviews = reviews;
        this.reports = reports;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
        this.scheduler = jdbcScheduler;
    }

    /** One abuse report per verified signed-in customer per review. */
    @PostMapping(value = "/products/{productId}/reviews/{reviewId}/reports/owner/{ownerKey}",
                 consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ReviewReportReceipt> report(@PathVariable int productId, @PathVariable Long reviewId,
                                            @PathVariable String ownerKey,
                                            @RequestBody ReviewReportRequest request) {
        validateIds(productId, reviewId);
        validateOwner(ownerKey);
        if (request == null || request.reasonCode() == null || !REASONS.contains(request.reasonCode()))
            throw new InvalidInputException("Unsupported report reason");
        return Mono.fromCallable(() -> {
            try {
                return tx.execute(status -> {
                    CustomerReviewEntity review = findReview(productId, reviewId);
                    if (review.getOwnerKey().equals(ownerKey))
                        throw new InvalidInputException("Cannot report your own review");
                    CustomerReviewReportEntity existing = reports.findByReviewIdAndReporterKey(reviewId, ownerKey)
                            .orElse(null);
                    if (existing != null) {
                        if (!existing.getReasonCode().equals(request.reasonCode()))
                            throw new InvalidInputException("Report reason cannot change");
                        return receipt(existing);
                    }
                    return receipt(reports.saveAndFlush(new CustomerReviewReportEntity(
                            reviewId, productId, ownerKey, request.reasonCode())));
                });
            } catch (DataIntegrityViolationException ex) {
                // Unique DB constraint also covers concurrent duplicate reports.
                CustomerReviewReportEntity existing = reports.findByReviewIdAndReporterKey(reviewId, ownerKey)
                        .orElseThrow(() -> ex);
                if (!existing.getReasonCode().equals(request.reasonCode()))
                    throw new InvalidInputException("Conflicting duplicate report");
                return receipt(existing);
            }
        }).subscribeOn(scheduler);
    }

    /** Only reachable via the composite's dedicated review-moderator scope. */
    @GetMapping("/reports")
    public Mono<ReviewReportPage> listReports(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        return Mono.fromCallable(() -> {
            Page<CustomerReviewReportEntity> found = reports.findAll(
                    PageRequest.of(page, size,
                            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
            return new ReviewReportPage(found.getContent().stream().map(this::receipt).toList(),
                    page, size, found.getTotalElements(), found.getTotalPages());
        }).subscribeOn(scheduler);
    }

    @GetMapping("/products/{productId}/reviews/{reviewId}")
    public Mono<ModerationReviewDetails> review(@PathVariable int productId, @PathVariable Long reviewId) {
        validateIds(productId, reviewId);
        return Mono.fromCallable(() -> details(findReview(productId, reviewId))).subscribeOn(scheduler);
    }

    @PostMapping(value = "/products/{productId}/reviews/{reviewId}/decision",
                 consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ModerationReviewDetails> decide(@PathVariable int productId, @PathVariable Long reviewId,
                                                 @RequestBody ReviewModerationRequest action) {
        validateIds(productId, reviewId);
        if (action == null || action.eventId() == null
                || !action.eventId().matches("[A-Za-z0-9._-]{8,100}")
                || action.action() == null || !Set.of("HIDE", "RESTORE").contains(action.action())
                || action.reasonCode() == null || !REASONS.contains(action.reasonCode()))
            throw new InvalidInputException("Invalid moderation event");
        return Mono.fromCallable(() -> tx.execute(status -> {
            ReviewModerationEventEntity replay = events.findById(action.eventId()).orElse(null);
            if (replay != null) {
                if (!replay.getReviewId().equals(reviewId)
                        || !replay.getAction().equals(action.action())
                        || !replay.getReasonCode().equals(action.reasonCode()))
                    throw new InvalidInputException("Conflicting moderation event replay");
                return details(findReview(productId, reviewId));
            }
            CustomerReviewEntity review = findReview(productId, reviewId);
            String next = "HIDE".equals(action.action()) ? "HIDDEN" : "VISIBLE";
            if (!next.equals(review.getVisibility())) {
                review.setVisibility(next);
                review.setUpdatedAt(java.time.Instant.now());
                reviews.saveAndFlush(review);
            }
            events.saveAndFlush(new ReviewModerationEventEntity(
                    action.eventId(), reviewId, action.action(), action.reasonCode()));
            return details(review);
        })).subscribeOn(scheduler);
    }

    private CustomerReviewEntity findReview(int productId, Long reviewId) {
        return reviews.findById(reviewId).filter(r -> r.getProductId() == productId)
                .orElseThrow(() -> new NotFoundException("Review not found"));
    }

    private ModerationReviewDetails details(CustomerReviewEntity r) {
        return new ModerationReviewDetails(r.getId(), r.getProductId(), r.getTitle(), r.getContent(),
                r.isMockPurchaseConfirmed(), r.getVisibility(), reports.countByReviewId(r.getId()));
    }

    private ReviewReportReceipt receipt(CustomerReviewReportEntity report) {
        return new ReviewReportReceipt(report.getReviewId(), report.getProductId(),
                report.getReasonCode(), report.getCreatedAt());
    }

    private void validateIds(int productId, Long reviewId) {
        if (productId < 1 || reviewId == null || reviewId < 1)
            throw new InvalidInputException("Invalid product or review ID");
    }

    private void validateOwner(String ownerKey) {
        if (ownerKey == null || !ownerKey.matches("[0-9a-f]{64}"))
            throw new InvalidInputException("Invalid customer identity");
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new InvalidInputException("page must be >= 0 and size must be 1-100");
    }
}
