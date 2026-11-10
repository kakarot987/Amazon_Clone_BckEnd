package amazon.microservices.composite.product.outbox;

import amazon.api.event.Event;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Single-file ordered dispatcher. Across replicas, a CAS lease ensures that
 * only one active worker claims an event. Ambiguous broker acknowledgements
 * may still produce at-least-once delivery; consumer inboxes deduplicate.
 */
@Component
public class OutboxDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int MAX_PER_PASS = 50;
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final ReactiveMongoTemplate mongo;
    private final ObjectMapper mapper;
    private final StreamBridge bridge;
    private final Scheduler scheduler;
    private final MeterRegistry meters;

    public OutboxDispatcher(ReactiveMongoTemplate mongo, ObjectMapper mapper,
                            StreamBridge bridge,
                            @Qualifier("publishEventScheduler") Scheduler scheduler,
                            MeterRegistry meters) {
        this.mongo=mongo;
        this.mapper=mapper;
        this.bridge=bridge;
        this.scheduler=scheduler;
        this.meters=meters;
    }

    @Scheduled(initialDelayString="${app.outbox.initial-delay-ms:3000}",
               fixedDelayString="${app.outbox.poll-delay-ms:1000}")
    public void poll() {
        dispatchOnce().subscribe(
                ignored -> {},
                ex -> LOG.warn("Outbox poll failed: {}", ex.getClass().getSimpleName()));
    }

    public Mono<Void> dispatchOnce() {
        // Stop at the first undeliverable head. Later records cannot overtake
        // an active lease or a delayed retry, even across multiple destinations.
        return Flux.range(0, MAX_PER_PASS)
                .concatMap(i -> dispatchHead())
                .takeUntil(processed -> !processed)
                .then();
    }

    private Mono<Boolean> dispatchHead() {
        Query oldest = Query.query(Criteria.where("state").in(
                        List.of(OutboxEntry.PENDING, OutboxEntry.IN_FLIGHT)))
                .with(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("_id")))
                .limit(1);
        return mongo.findOne(oldest, OutboxEntry.class)
                .flatMap(entry -> {
                    Instant now=Instant.now();
                    if (OutboxEntry.PENDING.equals(entry.getState())
                            && entry.getNextAttemptAt()!=null
                            && entry.getNextAttemptAt().isAfter(now))
                        return Mono.just(false);
                    if (OutboxEntry.IN_FLIGHT.equals(entry.getState())
                            && entry.getLeaseUntil()!=null
                            && entry.getLeaseUntil().isAfter(now))
                        return Mono.just(false);
                    String claim=UUID.randomUUID().toString();
                    Criteria eligible = OutboxEntry.PENDING.equals(entry.getState())
                            ? Criteria.where("state").is(OutboxEntry.PENDING)
                                .and("nextAttemptAt").lte(now)
                            : Criteria.where("state").is(OutboxEntry.IN_FLIGHT)
                                .and("leaseUntil").lte(now);
                    Query claimQuery=Query.query(new Criteria().andOperator(
                            Criteria.where("_id").is(entry.getId()),eligible));
                    Update update=new Update().set("state",OutboxEntry.IN_FLIGHT)
                            .set("leaseId",claim).set("leaseUntil",now.plus(LEASE))
                            .inc("attempts",1);
                    return mongo.findAndModify(claimQuery,update,
                            FindAndModifyOptions.options().returnNew(true),OutboxEntry.class)
                            .flatMap(owned -> publish(owned,claim))
                            .defaultIfEmpty(false);
                }).defaultIfEmpty(false);
    }

    private Mono<Boolean> publish(OutboxEntry entry,String claim) {
        return Mono.fromCallable(() -> {
                    Event<?,?> event=mapper.readValue(entry.getPayloadJson(),Event.class);
                    return bridge.send(entry.getBindingName(),
                            MessageBuilder.withPayload(event)
                                    .setHeader("partitionKey",entry.getPartitionKey())
                                    .build());
                })
                .subscribeOn(scheduler)
                .flatMap(accepted -> {
                    if (!accepted) return retry(entry,claim);
                    Query owned = claimQuery(entry.getId(),claim);
                    Update done=new Update().set("state",OutboxEntry.PUBLISHED)
                            .set("publishedAt",Instant.now())
                            .unset("leaseId").unset("leaseUntil");
                    return mongo.updateFirst(owned,done,OutboxEntry.class)
                            .map(result -> {
                                if (result.getModifiedCount()>0)
                                    Counter.builder("commerce.outbox.published").register(meters).increment();
                                return result.getModifiedCount()>0;
                            });
                })
                .onErrorResume(error -> {
                    LOG.warn("Outbox event {} delivery failed ({})",
                            entry.getId(),error.getClass().getSimpleName());
                    return retry(entry,claim);
                });
    }

    private Mono<Boolean> retry(OutboxEntry entry,String claim) {
        // Bounded exponential retry; never silently discard an event.
        int power=Math.min(8,Math.max(0,entry.getAttempts()-1));
        long seconds=Math.min(300L,1L<<power);
        Update waiting=new Update().set("state",OutboxEntry.PENDING)
                .set("nextAttemptAt",Instant.now().plusSeconds(seconds))
                .unset("leaseId").unset("leaseUntil");
        return mongo.updateFirst(claimQuery(entry.getId(),claim),waiting,OutboxEntry.class)
                .map(result -> {
                    if (result.getModifiedCount()>0)
                        Counter.builder("commerce.outbox.retries").register(meters).increment();
                    return false;
                });
    }

    private Query claimQuery(String id,String claim) {
        return Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(id),
                Criteria.where("state").is(OutboxEntry.IN_FLIGHT),
                Criteria.where("leaseId").is(claim)));
    }
}
