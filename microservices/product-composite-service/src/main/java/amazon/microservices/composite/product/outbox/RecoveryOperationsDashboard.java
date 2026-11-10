package amazon.microservices.composite.product.outbox;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * Non-mutating operational snapshot. Counts are independent queries, not a
 * transactional or globally consistent snapshot of the distributed system.
 */
@Service
public class RecoveryOperationsDashboard {
    private final ReactiveMongoTemplate mongo;

    public RecoveryOperationsDashboard(ReactiveMongoTemplate mongo) {
        this.mongo = mongo;
    }

    public record Head(String eventId, String operationId, String bindingName,
                       Integer productId, String state, int attempts,
                       Instant createdAt, Instant nextAttemptAt, Instant leaseUntil,
                       String readiness) {}

    public record Overview(Instant checkedAt, long pending, long checking, long waiting,
                           long receiptsVerified, long needsReview, Head oldestUndelivered) {}

    public Mono<Overview> get() {
        Mono<Long> pending=count(SagaSupervision.PENDING);
        Mono<Long> checking=count(SagaSupervision.CHECKING);
        Mono<Long> waiting=count(SagaSupervision.WAITING);
        Mono<Long> verified=count(SagaSupervision.VERIFIED);
        Mono<Long> review=count(SagaSupervision.NEEDS_REVIEW);

        Query first=Query.query(Criteria.where("state")
                        .in(List.of(OutboxEntry.PENDING, OutboxEntry.IN_FLIGHT)))
                .with(Sort.by(Sort.Order.asc("createdAt"),Sort.Order.asc("_id")))
                .limit(1);
        Mono<Head> head=mongo.findOne(first,OutboxEntry.class)
                .map(event -> {
                    Instant now=Instant.now();
                    String status;
                    if(OutboxEntry.PENDING.equals(event.getState())) {
                        status=event.getNextAttemptAt()!=null
                                && event.getNextAttemptAt().isAfter(now)
                                ? "RETRY_BACKOFF" : "READY";
                    } else {
                        status=event.getLeaseUntil()!=null && event.getLeaseUntil().isAfter(now)
                                ? "LEASE_ACTIVE" : "LEASE_EXPIRED";
                    }
                    // Do not expose the lease ID or serialized message payload.
                    return new Head(event.getId(),event.getOperationId(),event.getBindingName(),
                            event.getPartitionKey(),event.getState(),event.getAttempts(),
                            event.getCreatedAt(),event.getNextAttemptAt(),event.getLeaseUntil(),status);
                });
        return Mono.zip(pending,checking,waiting,verified,review)
                .flatMap(counts -> head
                        .map(event -> new Overview(Instant.now(),counts.getT1(),counts.getT2(),
                                counts.getT3(),counts.getT4(),counts.getT5(),event))
                        .defaultIfEmpty(new Overview(Instant.now(),counts.getT1(),counts.getT2(),
                                counts.getT3(),counts.getT4(),counts.getT5(),null)));
    }

    private Mono<Long> count(String state) {
        return mongo.count(Query.query(Criteria.where("state").is(state)),SagaSupervision.class);
    }
}
