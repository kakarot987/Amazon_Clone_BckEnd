package amazon.microservices.composite.product.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RecoveryTelemetryTests {
    private static final String PREFIX = "commerce.reliability.";

    private static void counts(ReactiveMongoTemplate mongo, Map<String, Long> values,
                               long outboxCount) {
        when(mongo.count(any(Query.class), eq(SagaSupervision.class))).thenAnswer(invocation -> {
            Query query = invocation.getArgument(0);
            String state = query.getQueryObject().getString("state");
            return Mono.just(values.getOrDefault(state, 0L));
        });
        when(mongo.count(any(Query.class), eq(OutboxEntry.class)))
                .thenReturn(Mono.just(outboxCount));
    }

    private static double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(PREFIX + name).gauge().value();
    }

    @Test
    void publishesOnlyAggregateCountsAndOldestAge() {
        ReactiveMongoTemplate mongo = mock(ReactiveMongoTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        counts(mongo, Map.of(
                SagaSupervision.PENDING, 3L,
                SagaSupervision.CHECKING, 1L,
                SagaSupervision.WAITING, 4L,
                SagaSupervision.VERIFIED, 10L,
                SagaSupervision.NEEDS_REVIEW, 2L), 7L);
        OutboxEntry head = new OutboxEntry("opaque-event-id", "products-out-0",
                "secret business payload", 901, Instant.now().minusSeconds(400));
        when(mongo.findOne(any(Query.class), eq(OutboxEntry.class)))
                .thenReturn(Mono.just(head));

        RecoveryTelemetry telemetry = new RecoveryTelemetry(mongo, registry);
        telemetry.refresh().block();

        assertEquals(3.0, gauge(registry, "saga.pending"));
        assertEquals(1.0, gauge(registry, "saga.checking"));
        assertEquals(4.0, gauge(registry, "saga.waiting"));
        assertEquals(10.0, gauge(registry, "saga.receipts.verified"));
        assertEquals(2.0, gauge(registry, "saga.needs.review"));
        assertEquals(7.0, gauge(registry, "outbox.undelivered"));
        assertTrue(gauge(registry, "outbox.oldest.age.seconds") >= 390.0);
        assertTrue(gauge(registry, "outbox.oldest.age.seconds") < 430.0);
        assertTrue(gauge(registry, "telemetry.last.success.epoch.seconds") > 0.0);
        assertTrue(registry.getMeters().stream().noneMatch(meter ->
                meter.getId().getName().contains("opaque-event-id")
                        || meter.getId().getName().contains("secret business payload")));
    }

    @Test
    void noUndeliveredEventsMeansZeroAgeWithoutInventingQueueHead() {
        ReactiveMongoTemplate mongo = mock(ReactiveMongoTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        counts(mongo, Map.of(), 0L);
        when(mongo.findOne(any(Query.class), eq(OutboxEntry.class)))
                .thenReturn(Mono.empty());

        RecoveryTelemetry telemetry = new RecoveryTelemetry(mongo, registry);
        telemetry.refresh().block();

        assertEquals(0.0, gauge(registry, "outbox.undelivered"));
        assertEquals(0.0, gauge(registry, "outbox.oldest.age.seconds"));
        assertEquals(0.0, gauge(registry, "saga.needs.review"));
        assertTrue(gauge(registry, "telemetry.last.success.epoch.seconds") > 0.0);
    }

    @Test
    void queryFailureKeepsLastKnownValuesAndStaleTimestamp() {
        ReactiveMongoTemplate mongo = mock(ReactiveMongoTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(mongo.count(any(Query.class), eq(SagaSupervision.class)))
                .thenReturn(Mono.error(new IllegalStateException("Mongo unavailable")));
        when(mongo.count(any(Query.class), eq(OutboxEntry.class)))
                .thenReturn(Mono.just(0L));
        when(mongo.findOne(any(Query.class), eq(OutboxEntry.class)))
                .thenReturn(Mono.empty());

        RecoveryTelemetry telemetry = new RecoveryTelemetry(mongo, registry);
        assertThrows(IllegalStateException.class, () -> telemetry.refresh().block());

        assertEquals(0.0, gauge(registry, "telemetry.last.success.epoch.seconds"));
        assertEquals(0.0, gauge(registry, "outbox.undelivered"));
        assertEquals(0.0, gauge(registry, "saga.pending"));
    }
}
