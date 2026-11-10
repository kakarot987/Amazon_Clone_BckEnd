package amazon.microservices.composite.product.reconciliation;

import amazon.api.core.product.Product;
import amazon.api.core.recommendation.Recommendation;
import amazon.api.core.review.Review;
import amazon.microservices.composite.product.outbox.OutboxEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Operator-only, read-only reconciliation of a bounded outbox history against
 * independently fetched downstream snapshots. No corrective writes are made.
 *
 * A matching snapshot is an observation, NOT a broker acknowledgement or a
 * distributed transaction. Consumers may be unavailable or change afterwards.
 */
@Service
public class AggregateReconciliationService {
    private static final int HISTORY_LIMIT = 100;
    private static final Duration SETTLE_WINDOW = Duration.ofMinutes(2);
    private static final String PRODUCTS = "products-out-0";
    private static final String RECOMMENDATIONS = "recommendations-out-0";
    private static final String REVIEWS = "reviews-out-0";

    private final ReactiveMongoTemplate mongo;
    private final WebClient client;
    private final ObjectMapper mapper;

    public AggregateReconciliationService(ReactiveMongoTemplate mongo, WebClient client,
                                          ObjectMapper mapper) {
        this.mongo = mongo;
        this.client = client;
        this.mapper = mapper;
    }

    /** Does not expose original review text, payloads or customer identifiers. */
    public record Check(String destination, String journal, String observation,
                        int expectedItems, int observedItems, String note) {}

    public record Report(int productId, Instant checkedAt, String status,
                         boolean truncated, int eventsScanned, List<Check> checks) {}

    private record Probe(boolean available, Set<Integer> ids) {
        static Probe unavailable() { return new Probe(false, Set.of()); }
        static Probe present(Set<Integer> ids) { return new Probe(true, ids); }
    }

    private static final class Expected {
        final String binding;
        boolean touched;
        boolean pending;
        boolean productPresent;
        Instant latestPublishedAt;
        final Set<Integer> ids = new HashSet<>();

        Expected(String binding) { this.binding = binding; }

        void apply(OutboxEntry entry, JsonNode event, int productId) {
            if (event == null || !event.path("key").canConvertToInt()
                    || event.path("key").asInt() != productId)
                throw new IllegalArgumentException("Invalid event key");
            String type = event.path("eventType").asText();
            if (!Set.of("CREATE", "UPDATE", "DELETE").contains(type))
                throw new IllegalArgumentException("Unsupported event type");
            touched = true;
            if (!OutboxEntry.PUBLISHED.equals(entry.getState()))
                pending = true;
            if (entry.getPublishedAt() != null &&
                    (latestPublishedAt == null || entry.getPublishedAt().isAfter(latestPublishedAt)))
                latestPublishedAt = entry.getPublishedAt();

            if (PRODUCTS.equals(binding)) {
                productPresent = !"DELETE".equals(type);
            } else if ("DELETE".equals(type)) {
                ids.clear();
            } else {
                String field = RECOMMENDATIONS.equals(binding) ? "recommendationId" : "reviewId";
                JsonNode itemId = event.path("data").path(field);
                if (!itemId.canConvertToInt() || itemId.asInt() < 1)
                    throw new IllegalArgumentException("Invalid child ID in outbox");
                ids.add(itemId.asInt());
            }
        }

        Set<Integer> expectedIds() {
            return PRODUCTS.equals(binding)
                    ? (productPresent ? Set.of(1) : Set.of()) : Set.copyOf(ids);
        }
    }

    public Mono<Report> inspect(int productId) {
        if (productId < 1)
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "productId must be positive"));

        Query newest = Query.query(Criteria.where("partitionKey").is(productId))
                .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                .limit(HISTORY_LIMIT + 1);
        return mongo.find(newest, OutboxEntry.class).collectList().flatMap(entries -> {
            Instant checked = Instant.now();
            if (entries.isEmpty())
                return Mono.just(new Report(productId, checked, "NOT_TRACKED", false, 0, List.of()));

            // A partial journal cannot establish expected downstream state.
            if (entries.size() > HISTORY_LIMIT)
                return Mono.just(new Report(productId, checked, "INCOMPLETE_HISTORY",
                        true, HISTORY_LIMIT, List.of()));

            Collections.reverse(entries);
            Expected product = new Expected(PRODUCTS);
            Expected recommendation = new Expected(RECOMMENDATIONS);
            Expected review = new Expected(REVIEWS);
            try {
                for (OutboxEntry entry : entries) {
                    Expected target = switch (entry.getBindingName()) {
                        case PRODUCTS -> product;
                        case RECOMMENDATIONS -> recommendation;
                        case REVIEWS -> review;
                        default -> throw new IllegalArgumentException("Unknown destination");
                    };
                    target.apply(entry, mapper.readTree(entry.getPayloadJson()), productId);
                }
            } catch (Exception corrupted) {
                // Fail inconclusive; never guess the desired state from bad journal data.
                return Mono.just(new Report(productId, checked, "INVALID_JOURNAL",
                        false, entries.size(), List.of()));
            }

            return Mono.zip(
                    evaluate(product, checked, probeProduct(productId)),
                    evaluate(recommendation, checked, probeRecommendations(productId)),
                    evaluate(review, checked, probeReviews(productId))
            ).map(result -> {
                List<Check> checks = List.of(result.getT1(), result.getT2(), result.getT3());
                List<String> observations = checks.stream().map(Check::observation).toList();
                String status;
                if (observations.contains("DRIFT_CANDIDATE")) status = "DRIFT_CANDIDATE";
                else if (observations.contains("UNAVAILABLE")) status = "INCONCLUSIVE";
                else if (observations.contains("CONVERGING")) status = "CONVERGING";
                else if (observations.contains("PUBLISH_PENDING")) status = "PUBLISH_PENDING";
                else status = "OBSERVED";
                return new Report(productId, checked, status, false, entries.size(), checks);
            });
        });
    }

    private Mono<Check> evaluate(Expected expected, Instant checkedAt, Mono<Probe> source) {
        String destination = expected.binding;
        if (!expected.touched)
            return Mono.just(new Check(destination, "NOT_TRACKED", "NOT_CHECKED",
                    0, -1, "No outgoing journal entries for this destination"));
        if (expected.pending)
            return Mono.just(new Check(destination, "PENDING_OR_IN_FLIGHT", "PUBLISH_PENDING",
                    expected.expectedIds().size(), -1, "At least one event has not been marked published"));

        // Nothing probes downstream until all tracked destination events have
        // been accepted by StreamBridge. This is not a durable publisher confirm.
        return source.map(observed -> {
            Set<Integer> desired = expected.expectedIds();
            if (!observed.available)
                return new Check(destination, "PUBLISHED_ACCEPTED", "UNAVAILABLE",
                        desired.size(), -1, "Downstream response could not be verified");
            if (desired.equals(observed.ids))
                return new Check(destination, "PUBLISHED_ACCEPTED", "MATCHED",
                        desired.size(), observed.ids.size(), "Matching read-only snapshot");

            // With a missing publish timestamp, a difference is unverified.
            String status = expected.latestPublishedAt != null &&
                    expected.latestPublishedAt.plus(SETTLE_WINDOW).isBefore(checkedAt)
                    ? "DRIFT_CANDIDATE" : "CONVERGING";
            return new Check(destination, "PUBLISHED_ACCEPTED", status,
                    desired.size(), observed.ids.size(), "Observed IDs differ from latest tracked intent");
        });
    }

    private Mono<Probe> probeProduct(int productId) {
        return client.get().uri("http://product/product/{productId}", productId)
                .exchangeToMono(response -> {
                    if (response.statusCode().value() == 404)
                        return response.releaseBody().thenReturn(Probe.present(Set.of()));
                    if (!response.statusCode().is2xxSuccessful())
                        return response.releaseBody().thenReturn(Probe.unavailable());
                    return response.bodyToMono(Product.class)
                            .map(product -> product.getProductId() == productId
                                    ? Probe.present(Set.of(1)) : Probe.unavailable())
                            .defaultIfEmpty(Probe.unavailable());
                }).onErrorReturn(Probe.unavailable());
    }

    private Mono<Probe> probeRecommendations(int productId) {
        return client.get().uri("http://recommendation/recommendation?productId={productId}", productId)
                .retrieve().bodyToFlux(Recommendation.class)
                .map(Recommendation::getRecommendationId)
                .collect(Collectors.toSet())
                .map(Probe::present).onErrorReturn(Probe.unavailable());
    }

    private Mono<Probe> probeReviews(int productId) {
        return client.get().uri("http://review/review?productId={productId}", productId)
                .retrieve().bodyToFlux(Review.class)
                .map(Review::getReviewId)
                .collect(Collectors.toSet())
                .map(Probe::present).onErrorReturn(Probe.unavailable());
    }
}
