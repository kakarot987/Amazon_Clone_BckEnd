package amazon.microservices.composite.product;

import amazon.api.core.product.Product;
import amazon.api.event.Event;
import amazon.api.event.EventFingerprint;
import amazon.microservices.composite.product.outbox.AggregateOperation;
import amazon.microservices.composite.product.outbox.ConsumerReceiptAuditService;
import amazon.microservices.composite.product.outbox.OutboxEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ConsumerReceiptAuditServiceTests {
    private static final String SECRET = "test-only-read-probe-secret-at-least-32-bytes";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private record Fixture(AggregateOperation operation, OutboxEntry outbox, String fingerprint) {}

    private Fixture fixture(String state) throws Exception {
        String operationId = UUID.randomUUID().toString();
        Event<Integer, Product> event = new Event<>(Event.Type.CREATE, 901,
                new Product(901, "Receipt test", 1, null));
        OutboxEntry entry = new OutboxEntry(event.getEventId(), "products-out-0",
                mapper.writeValueAsString(event), 901, Instant.now());
        entry.setState(state);
        entry.setOperationId(operationId);
        AggregateOperation op = new AggregateOperation(operationId, Instant.now(), List.of(901),
                List.of(new AggregateOperation.Step(event.getEventId(), 901,
                        "products-out-0", "CREATE")));
        return new Fixture(op, entry, EventFingerprint.of(event, mapper));
    }

    private ConsumerReceiptAuditService service(Fixture f, HttpStatus responseStatus,
                                                String receiptJson, AtomicInteger probes,
                                                boolean includeOutbox) {
        ReactiveMongoTemplate mongo = mock(ReactiveMongoTemplate.class);
        when(mongo.findById(eq(f.operation().getId()), eq(AggregateOperation.class)))
                .thenReturn(Mono.just(f.operation()));
        when(mongo.find(any(Query.class), eq(OutboxEntry.class)))
                .thenReturn(includeOutbox ? Flux.just(f.outbox()) : Flux.empty());
        WebClient client = WebClient.builder().exchangeFunction(req -> {
            probes.incrementAndGet();
            return Mono.just(ClientResponse.create(responseStatus)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(receiptJson).build());
        }).build();
        return new ConsumerReceiptAuditService(mongo, client, mapper, SECRET);
    }

    @Test
    void matchesCommittedInboxFingerprintOnly() throws Exception {
        Fixture f = fixture(OutboxEntry.PUBLISHED);
        String json = mapper.writeValueAsString(
                new amazon.api.event.ReceiptEvidence(f.outbox().getId(), f.fingerprint(), Instant.now()));
        AtomicInteger probes = new AtomicInteger();
        var result = service(f, HttpStatus.OK, json, probes, true)
                .inspect(f.operation().getId()).block();
        assertNotNull(result);
        assertEquals("ALL_RECEIPTS_VERIFIED", result.status());
        assertEquals(1, result.verifiedEvents());
        assertEquals(1, probes.get());
    }

    @Test
    void mismatchedFingerprintIsConflict() throws Exception {
        Fixture f = fixture(OutboxEntry.PUBLISHED);
        String json = mapper.writeValueAsString(
                new amazon.api.event.ReceiptEvidence(f.outbox().getId(), "wrong", Instant.now()));
        AtomicInteger probes = new AtomicInteger();
        var result = service(f, HttpStatus.OK, json, probes, true)
                .inspect(f.operation().getId()).block();
        assertNotNull(result);
        assertEquals("CONFLICT", result.status());
        assertEquals(0, result.verifiedEvents());
    }

    @Test
    void missingReceiptIsNotDeclaredACompensationFailure() throws Exception {
        Fixture f = fixture(OutboxEntry.PUBLISHED);
        var result = service(f, HttpStatus.NOT_FOUND, "", new AtomicInteger(), true)
                .inspect(f.operation().getId()).block();
        assertNotNull(result);
        assertEquals("RECEIPTS_MISSING", result.status());
    }

    @Test
    void pendingPublisherCannotBeReportedAsCommitted() throws Exception {
        Fixture f = fixture(OutboxEntry.PENDING);
        AtomicInteger probes = new AtomicInteger();
        var result = service(f, HttpStatus.OK, "", probes, true)
                .inspect(f.operation().getId()).block();
        assertNotNull(result);
        assertEquals("PUBLISH_PENDING", result.status());
        assertEquals(0, probes.get());
    }

    @Test
    void missingOutboxFailsClosedWithoutProbe() throws Exception {
        Fixture f = fixture(OutboxEntry.PUBLISHED);
        AtomicInteger probes = new AtomicInteger();
        var result = service(f, HttpStatus.OK, "", probes, false)
                .inspect(f.operation().getId()).block();
        assertNotNull(result);
        assertEquals("INCOMPLETE_JOURNAL", result.status());
        assertEquals(0, probes.get());
    }

    @Test
    void unavailableConsumerIsInconclusiveNotMissing() throws Exception {
        Fixture f = fixture(OutboxEntry.PUBLISHED);
        var result = service(f, HttpStatus.SERVICE_UNAVAILABLE, "", new AtomicInteger(), true)
                .inspect(f.operation().getId()).block();
        assertNotNull(result);
        assertEquals("INCONCLUSIVE", result.status());
    }
}
