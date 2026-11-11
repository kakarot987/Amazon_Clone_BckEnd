package amazon.microservices.composite.product.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-cardinality, read-only MongoDB observations for outbox and saga health.
 * Values come from independent queries and are NOT a globally consistent view.
 *
 * A failed refresh preserves previous values and leaves lastSuccess unchanged,
 * so operators can detect stale telemetry instead of trusting zeroes.
 */
@Component
public class RecoveryTelemetry {
    private static final Logger LOG = LoggerFactory.getLogger(RecoveryTelemetry.class);
    private final ReactiveMongoTemplate mongo;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong checking = new AtomicLong();
    private final AtomicLong waiting = new AtomicLong();
    private final AtomicLong verified = new AtomicLong();
    private final AtomicLong needsReview = new AtomicLong();
    private final AtomicLong outboxUndelivered = new AtomicLong();
    private final AtomicLong quarantined = new AtomicLong();
    private final AtomicLong oldestUndeliveredAgeSeconds = new AtomicLong();
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();

    public RecoveryTelemetry(ReactiveMongoTemplate mongo, MeterRegistry metrics) {
        this.mongo = mongo;
        gauge(metrics, "commerce.reliability.saga.pending", pending);
        gauge(metrics, "commerce.reliability.saga.checking", checking);
        gauge(metrics, "commerce.reliability.saga.waiting", waiting);
        gauge(metrics, "commerce.reliability.saga.receipts.verified", verified);
        gauge(metrics, "commerce.reliability.saga.needs.review", needsReview);
        gauge(metrics, "commerce.reliability.outbox.undelivered", outboxUndelivered);
        gauge(metrics, "commerce.reliability.outbox.quarantined", quarantined);
        gauge(metrics, "commerce.reliability.outbox.oldest.age.seconds", oldestUndeliveredAgeSeconds);
        gauge(metrics, "commerce.reliability.telemetry.last.success.epoch.seconds", lastSuccessEpochSeconds);
    }

    private static void gauge(MeterRegistry registry, String name, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::get)
                .description("Read-only composite Mongo recovery observation")
                .register(registry);
    }

    @Scheduled(initialDelayString = "${app.recovery.telemetry.initial-delay-ms:15000}",
            fixedDelayString = "${app.recovery.telemetry.poll-delay-ms:30000}")
    public void poll() {
        if (!refreshing.compareAndSet(false, true))
            return;
        refresh().doFinally(signal -> refreshing.set(false))
                .subscribe(ignored -> {},
                        failure -> LOG.warn("Recovery telemetry refresh failed ({})",
                                failure.getClass().getSimpleName()));
    }

    /** Visible to package tests; never writes to Mongo or RabbitMQ. */
    Mono<Void> refresh() {
        Query oldest = Query.query(Criteria.where("state")
                        .in(List.of(OutboxEntry.PENDING, OutboxEntry.IN_FLIGHT, OutboxEntry.QUARANTINED)))
                .with(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("_id")))
                .limit(1);

        Mono<Optional<OutboxEntry>> head = mongo.findOne(oldest, OutboxEntry.class)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());

        return Mono.zip(
                count(SagaSupervision.PENDING),
                count(SagaSupervision.CHECKING),
                count(SagaSupervision.WAITING),
                count(SagaSupervision.VERIFIED),
                count(SagaSupervision.NEEDS_REVIEW),
                mongo.count(Query.query(Criteria.where("state")
                        .in(List.of(OutboxEntry.PENDING, OutboxEntry.IN_FLIGHT))),
                        OutboxEntry.class),
                mongo.count(Query.query(Criteria.where("state").is(OutboxEntry.QUARANTINED)),
                        OutboxEntry.class))
                .flatMap(counts -> head.doOnNext(observed -> {
                    Instant now = Instant.now();
                    long age = observed
                            .map(row -> row.getCreatedAt() == null ? 0L :
                                    Math.max(0L, now.getEpochSecond() - row.getCreatedAt().getEpochSecond()))
                            .orElse(0L);

                    pending.set(counts.getT1());
                    checking.set(counts.getT2());
                    waiting.set(counts.getT3());
                    verified.set(counts.getT4());
                    needsReview.set(counts.getT5());
                    outboxUndelivered.set(counts.getT6());
                    quarantined.set(counts.getT7());
                    oldestUndeliveredAgeSeconds.set(age);
                    // Advance only when all queries complete successfully.
                    lastSuccessEpochSeconds.set(now.getEpochSecond());
                })).then();
    }

    private Mono<Long> count(String state) {
        return mongo.count(Query.query(Criteria.where("state").is(state)),
                SagaSupervision.class);
    }
}
