package amazon.microservices.composite.product.outbox;

import amazon.api.event.Event;
import amazon.api.event.EventFingerprint;
import amazon.api.event.ReceiptEvidence;
import amazon.api.event.ReceiptProbeAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only signed probes of transactionally committed consumer inboxes. */
@Service
public class ConsumerReceiptAuditService {
    private final ReactiveMongoTemplate mongo;
    private final WebClient client;
    private final ObjectMapper mapper;
    private final String secret;

    public ConsumerReceiptAuditService(ReactiveMongoTemplate mongo, WebClient client,
            ObjectMapper mapper, @Value("${APP_RECEIPT_PROBE_SECRET:}") String secret) {
        this.mongo = mongo;
        this.client = client;
        this.mapper = mapper;
        this.secret = secret;
    }

    public record Evidence(String eventId, String bindingName, String status, Instant processedAt) {}
    public record Audit(String operationId, String status, Instant checkedAt,
                        int expectedEvents, int verifiedEvents, List<Evidence> events) {}

    public Mono<Audit> inspect(String operationId) {
        if (operationId == null || !operationId.matches("[0-9a-fA-F-]{36}"))
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid operation ID"));
        if (!ReceiptProbeAuth.configured(secret))
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Receipt verification not configured"));
        return mongo.findById(operationId, AggregateOperation.class)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown operation ID")))
                .flatMap(this::audit);
    }

    private Mono<Audit> audit(AggregateOperation operation) {
        List<AggregateOperation.Step> steps = operation.getSteps();
        if (steps == null || steps.isEmpty() || steps.size() > 100
                || steps.stream().anyMatch(step -> step == null
                        || step.getEventId() == null || step.getBindingName() == null)
                || steps.stream().map(AggregateOperation.Step::getEventId).distinct().count() != steps.size())
            return Mono.just(new Audit(operation.getId(), "INCOMPLETE_JOURNAL",
                    Instant.now(), steps == null ? 0 : steps.size(), 0, List.of()));
        List<String> ids = steps.stream().map(AggregateOperation.Step::getEventId).toList();
        return mongo.find(Query.query(Criteria.where("_id").in(ids)), OutboxEntry.class)
                .collectMap(OutboxEntry::getId)
                .flatMap(records -> {
                    boolean valid = records.size() == steps.size();
                    for (AggregateOperation.Step step : steps) {
                        OutboxEntry entry = records.get(step.getEventId());
                        if (entry == null || !operation.getId().equals(entry.getOperationId())
                                || !step.getBindingName().equals(entry.getBindingName())
                                || entry.getPartitionKey() == null
                                || step.getProductId() != entry.getPartitionKey()
                                || entry.getPayloadJson() == null) {
                            valid = false;
                            break;
                        }
                    }
                    if (!valid)
                        return Mono.just(new Audit(operation.getId(), "INCOMPLETE_JOURNAL",
                                Instant.now(), steps.size(), 0, List.of()));
                    return Flux.fromIterable(steps)
                            .concatMap(step -> probe(step, records.get(step.getEventId())))
                            .collectList()
                            .map(evidence -> {
                                int verified = (int) evidence.stream()
                                        .filter(e -> "VERIFIED".equals(e.status())).count();
                                Set<String> statuses = new HashSet<>();
                                evidence.forEach(e -> statuses.add(e.status()));
                                String status;
                                if (statuses.contains("CONFLICT") || statuses.contains("INVALID_EVENT"))
                                    status = "CONFLICT";
                                else if (statuses.contains("UNAVAILABLE"))
                                    status = "INCONCLUSIVE";
                                else if (statuses.contains("MISSING"))
                                    status = "RECEIPTS_MISSING";
                                else if (statuses.contains("PUBLISH_PENDING"))
                                    status = "PUBLISH_PENDING";
                                else if (verified == evidence.size())
                                    status = "ALL_RECEIPTS_VERIFIED";
                                else status = "INCONCLUSIVE";
                                return new Audit(operation.getId(), status, Instant.now(),
                                        steps.size(), verified, evidence);
                            });
                });
    }

    private Mono<Evidence> probe(AggregateOperation.Step step, OutboxEntry entry) {
        String id = step.getEventId();
        String binding = step.getBindingName();
        Event<?, ?> original;
        try {
            original = mapper.readValue(entry.getPayloadJson(), Event.class);
            if (!id.equals(original.getEventId()) || original.getEventType() == null
                    || !original.getEventType().name().equals(step.getEventType())
                    || !String.valueOf(step.getProductId()).equals(String.valueOf(original.getKey())))
                return Mono.just(new Evidence(id, binding, "INVALID_EVENT", null));
        } catch (Exception invalid) {
            return Mono.just(new Evidence(id, binding, "INVALID_EVENT", null));
        }
        String fingerprint;
        try {
            fingerprint = EventFingerprint.of(original, mapper);
        } catch (Exception invalid) {
            return Mono.just(new Evidence(id, binding, "INVALID_EVENT", null));
        }
        if (!OutboxEntry.PUBLISHED.equals(entry.getState()))
            return Mono.just(new Evidence(id, binding, "PUBLISH_PENDING", null));
        String target = switch (binding) {
            case "products-out-0" -> "product";
            case "recommendations-out-0" -> "recommendation";
            case "reviews-out-0" -> "review";
            default -> null;
        };
        if (target == null)
            return Mono.just(new Evidence(id, binding, "INVALID_EVENT", null));
        long now = Instant.now().getEpochSecond();
        String signature = ReceiptProbeAuth.sign(secret, binding, id, now);
        return client.get().uri("http://" + target + "/internal/event-receipts/{id}", id)
                .header("X-Receipt-Timestamp", Long.toString(now))
                .header("X-Receipt-Signature", signature)
                .exchangeToMono(response -> {
                    if (response.statusCode().value() == 404)
                        return response.releaseBody().thenReturn(new Evidence(id, binding, "MISSING", null));
                    if (!response.statusCode().is2xxSuccessful())
                        return response.releaseBody().thenReturn(new Evidence(id, binding, "UNAVAILABLE", null));
                    return response.bodyToMono(ReceiptEvidence.class)
                            .map(receipt -> id.equals(receipt.eventId())
                                    && fingerprint.equals(receipt.fingerprint())
                                    && receipt.processedAt() != null
                                    ? new Evidence(id, binding, "VERIFIED", receipt.processedAt())
                                    : new Evidence(id, binding, "CONFLICT", null))
                            .defaultIfEmpty(new Evidence(id, binding, "UNAVAILABLE", null));
                })
                .timeout(Duration.ofSeconds(3))
                .onErrorReturn(new Evidence(id, binding, "UNAVAILABLE", null));
    }
}
