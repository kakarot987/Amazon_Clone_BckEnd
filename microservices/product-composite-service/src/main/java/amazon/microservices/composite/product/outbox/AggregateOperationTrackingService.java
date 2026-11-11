package amazon.microservices.composite.product.outbox;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives publication progress from durable operation/outbox journals.
 * BROKER_ACCEPTED = StreamBridge accepted; consumer completion is UNKNOWN.
 */
@Service
public class AggregateOperationTrackingService {
    private static final int MAX_LIST = 20;
    private final ReactiveMongoTemplate mongo;

    public AggregateOperationTrackingService(ReactiveMongoTemplate mongo) {
        this.mongo = mongo;
    }

    public record EventProgress(String eventId, String bindingName, String eventType,
                                int productId, String state, int attempts,
                                Instant nextAttemptAt, Instant publishedAt) {}

    public record Report(String operationId, Instant createdAt, List<Integer> productIds,
                         String publicationStatus, String consumerStatus,
                         int expectedEvents, int publishedEvents,
                         int pendingEvents, int inFlightEvents, List<EventProgress> events) {}

    public Mono<List<Report>> list(int productId, int limit) {
        if (productId < 1 || limit < 1 || limit > MAX_LIST)
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "productId must be positive and limit must be 1-20"));
        Query query = Query.query(Criteria.where("productIds").is(productId))
                .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                .limit(limit);
        return mongo.find(query, AggregateOperation.class)
                .concatMap(this::inspectOperation)
                .collectList();
    }

    public Mono<Report> get(String operationId) {
        if (operationId == null || !operationId.matches("[0-9a-fA-F-]{36}"))
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid operation ID"));
        return mongo.findById(operationId, AggregateOperation.class)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown operation ID")))
                .flatMap(this::inspectOperation);
    }

    private Mono<Report> inspectOperation(AggregateOperation operation) {
        if (operation.getSteps() == null || operation.getSteps().isEmpty()
                || operation.getSteps().size() > 100
                || operation.getProductIds() == null || operation.getProductIds().isEmpty())
            return Mono.just(invalid(operation));

        List<String> ids = operation.getSteps().stream().map(AggregateOperation.Step::getEventId).toList();
        Set<String> unique = new HashSet<>(ids);
        if (unique.size() != ids.size() || unique.contains(null))
            return Mono.just(invalid(operation));

        return mongo.find(Query.query(Criteria.where("_id").in(ids)), OutboxEntry.class)
                .collectMap(OutboxEntry::getId)
                .map(records -> summarize(operation, records));
    }

    private Report invalid(AggregateOperation operation) {
        return new Report(operation.getId(), operation.getCreatedAt(), operation.getProductIds(),
                "INCOMPLETE_JOURNAL", "NOT_VERIFIED",
                operation.getSteps() == null ? 0 : operation.getSteps().size(),
                0, 0, 0, List.of());
    }

    private Report summarize(AggregateOperation operation, Map<String, OutboxEntry> records) {
        int published = 0, pending = 0, inFlight = 0;
        boolean quarantined = false;
        boolean corrupt = false, unknown = false;
        List<EventProgress> progress = new java.util.ArrayList<>();

        for (AggregateOperation.Step step : operation.getSteps()) {
            OutboxEntry entry = records.get(step.getEventId());
            if (entry == null
                    || !operation.getId().equals(entry.getOperationId())
                    || !step.getBindingName().equals(entry.getBindingName())
                    || entry.getPartitionKey() == null
                    || step.getProductId() != entry.getPartitionKey()) {
                corrupt = true;
                continue;
            }

            String state = entry.getState();
            switch (state == null ? "" : state) {
                case OutboxEntry.PUBLISHED -> published++;
                case OutboxEntry.PENDING -> pending++;
                case OutboxEntry.IN_FLIGHT -> inFlight++;
                case OutboxEntry.QUARANTINED -> quarantined = true;
                default -> unknown = true;
            }
            progress.add(new EventProgress(step.getEventId(), step.getBindingName(),
                    step.getEventType(), step.getProductId(), state,
                    entry.getAttempts(), entry.getNextAttemptAt(), entry.getPublishedAt()));
        }

        String status = corrupt || records.size() != operation.getSteps().size()
                ? "INCOMPLETE_JOURNAL"
                : unknown ? "UNKNOWN_STATE"
                : quarantined ? "QUARANTINED"
                : inFlight > 0 ? "DISPATCHING"
                : pending > 0 ? "PUBLISH_PENDING"
                : published == operation.getSteps().size() ? "BROKER_ACCEPTED" : "UNKNOWN_STATE";

        return new Report(operation.getId(), operation.getCreatedAt(), operation.getProductIds(),
                status, "NOT_VERIFIED", operation.getSteps().size(), published, pending,
                inFlight, List.copyOf(progress));
    }
}
