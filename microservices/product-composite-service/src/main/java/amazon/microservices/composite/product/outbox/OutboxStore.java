package amazon.microservices.composite.product.outbox;

import amazon.api.event.Event;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.TreeSet;

/**
 * Acknowledges API writes only after all outgoing events are persisted.
 * A multi-event aggregate is a single Mongo transaction, not multiple sends.
 */
@Component
public class OutboxStore {
    private static final Set<String> BINDINGS = Set.of(
            "products-out-0", "recommendations-out-0", "reviews-out-0");
    private static final int MAX_BATCH = 100;
    private static final int MAX_EVENT_CHARS = 128_000;

    private final ReactiveMongoTemplate mongo;
    private final TransactionalOperator transactions;
    private final ObjectMapper mapper;

    public OutboxStore(ReactiveMongoTemplate mongo,
                       TransactionalOperator outboxTransactions,
                       ObjectMapper mapper) {
        this.mongo=mongo;
        this.transactions=outboxTransactions;
        this.mapper=mapper;
    }

    public Mono<Void> enqueue(List<OutboundEvent> outbound) {
        return Mono.defer(() -> {
            if (outbound == null || outbound.isEmpty() || outbound.size() > MAX_BATCH)
                return Mono.error(new IllegalArgumentException("Outbox batch must contain 1-100 events"));
            List<OutboxEntry> batch = new ArrayList<>(outbound.size());
            List<AggregateOperation.Step> steps = new ArrayList<>(outbound.size());
            Set<Integer> productIds = new TreeSet<>();
            String operationId = UUID.randomUUID().toString();
            Instant now = Instant.now();
            int n = 0;
            for (OutboundEvent message : outbound) {
                if (message == null || !BINDINGS.contains(message.bindingName())
                        || message.event() == null || message.event().getEventId() == null
                        || message.event().getEventType() == null
                        || message.event().getKey() == null
                        || !(message.event().getKey() instanceof Integer key) || key < 1)
                    return Mono.error(new IllegalArgumentException("Invalid outbox destination or event"));
                Event<?, ?> event = message.event();
                try {
                    String json = mapper.writeValueAsString(event);
                    if (json.length() > MAX_EVENT_CHARS)
                        return Mono.error(new IllegalArgumentException("Outbox event exceeds payload limit"));
                    // Preserve within-batch order across crashes and dispatch retries.
                    OutboxEntry entry = new OutboxEntry(event.getEventId(), message.bindingName(),
                            json, key, now.plusNanos(n++));
                    entry.setOperationId(operationId);
                    batch.add(entry);
                    steps.add(new AggregateOperation.Step(event.getEventId(), key,
                            message.bindingName(), event.getEventType().name()));
                    productIds.add(key);
                } catch (JsonProcessingException ex) {
                    return Mono.error(new IllegalArgumentException("Cannot serialize event payload", ex));
                }
            }
            AggregateOperation operation = new AggregateOperation(operationId, now,
                    new ArrayList<>(productIds), steps);
            // Operation intent and every event commit/rollback as ONE Mongo transaction.
            return mongo.insert(operation)
                    .then(mongo.insert(batch, OutboxEntry.class).then())
                    .as(transactions::transactional);
        });
    }
}
