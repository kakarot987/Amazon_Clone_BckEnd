package amazon.microservices.composite.product;

import amazon.microservices.composite.product.outbox.OutboxEntry;
import amazon.microservices.composite.product.reconciliation.AggregateReconciliationService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AggregateReconciliationServiceTests {
    private static final int PRODUCT_ID = 923;
    private static final String PRODUCT = "products-out-0";
    private static final String RECOMMENDATION = "recommendations-out-0";
    private static final String REVIEW = "reviews-out-0";

    private record Downstream(HttpStatus product, String recommendations,
                              String reviews, AtomicInteger requests) {}

    private static OutboxEntry entry(String id, String binding, String type, int childId,
                                     String state, Instant publishedAt) {
        String data = childId > 0
                ? (REVIEW.equals(binding) ? "{\"reviewId\":" + childId + "}"
                   : "{\"recommendationId\":" + childId + "}") : "null";
        String payload = "{\"eventType\":\"" + type + "\",\"key\":" + PRODUCT_ID
                + ",\"data\":" + data + "}";
        OutboxEntry entry = new OutboxEntry(id, binding, payload, PRODUCT_ID, Instant.now());
        entry.setState(state);
        entry.setPublishedAt(publishedAt);
        return entry;
    }

    private static OutboxEntry accepted(String id, String binding, String type, int childId) {
        return entry(id, binding, type, childId, OutboxEntry.PUBLISHED,
                Instant.now().minusSeconds(300));
    }

    private static WebClient simulatedServices(HttpStatus productStatus,
                                               String recommendations, String reviews,
                                               AtomicInteger requestCount) {
        return WebClient.builder().exchangeFunction(request -> {
            requestCount.incrementAndGet();
            String host = request.url().getHost();
            HttpStatus status;
            String json;
            switch (host) {
                case "product" -> {
                    status = productStatus;
                    json = "{\"productId\":" + PRODUCT_ID + "}";
                }
                case "recommendation" -> {
                    status = HttpStatus.OK;
                    json = recommendations;
                }
                case "review" -> {
                    status = HttpStatus.OK;
                    json = reviews;
                }
                default -> throw new IllegalArgumentException("Unexpected host " + host);
            }
            return Mono.just(ClientResponse.create(status)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(json).build());
        }).build();
    }

    private static AggregateReconciliationService service(List<OutboxEntry> history,
                                                          HttpStatus productStatus,
                                                          String recommendations, String reviews,
                                                          AtomicInteger requests) {
        ReactiveMongoTemplate mongo = mock(ReactiveMongoTemplate.class);
        when(mongo.find(any(Query.class), eq(OutboxEntry.class)))
                .thenReturn(Flux.fromIterable(history));
        return new AggregateReconciliationService(mongo,
                simulatedServices(productStatus, recommendations, reviews, requests),
                new ObjectMapper());
    }

    @Test
    void matchingPublishedCreateEventsAreObservedButNotConfirmed() {
        AtomicInteger calls = new AtomicInteger();
        var audit = service(List.of(
                accepted("prod-event-a", PRODUCT, "CREATE", 0),
                accepted("rec-event-a", RECOMMENDATION, "CREATE", 9),
                accepted("rev-event-a", REVIEW, "CREATE", 7)),
                HttpStatus.OK, "[{\"recommendationId\":9}]", "[{\"reviewId\":7}]", calls)
                .inspect(PRODUCT_ID).block();

        assertNotNull(audit);
        assertEquals("OBSERVED", audit.status());
        assertEquals(3, audit.eventsScanned());
        assertEquals(3, calls.get());
        assertTrue(audit.checks().stream().allMatch(c -> "MATCHED".equals(c.observation())));
    }

    @Test
    void missingReviewAfterSettleWindowIsOnlyADriftCandidate() {
        AtomicInteger calls = new AtomicInteger();
        var audit = service(List.of(
                accepted("prod-event-b", PRODUCT, "CREATE", 0),
                accepted("rev-event-b", REVIEW, "CREATE", 7)),
                HttpStatus.OK, "[]", "[]", calls).inspect(PRODUCT_ID).block();
        assertNotNull(audit);
        assertEquals("DRIFT_CANDIDATE", audit.status());
        assertTrue(audit.checks().stream().anyMatch(c ->
                REVIEW.equals(c.destination()) && "DRIFT_CANDIDATE".equals(c.observation())));
        assertEquals(2, calls.get());
    }

    @Test
    void aRecentPublisherAcceptanceIsNotMisreportedAsDrift() {
        AtomicInteger calls = new AtomicInteger();
        var audit = service(List.of(
                entry("rev-event-c", REVIEW, "CREATE", 3,
                        OutboxEntry.PUBLISHED, Instant.now())),
                HttpStatus.OK, "[]", "[]", calls).inspect(PRODUCT_ID).block();
        assertNotNull(audit);
        assertEquals("CONVERGING", audit.status());
    }

    @Test
    void pendingPublicationDoesNotProbeDownstream() {
        AtomicInteger calls = new AtomicInteger();
        var audit = service(List.of(
                entry("prod-event-d", PRODUCT, "CREATE", 0,
                        OutboxEntry.PENDING, null)),
                HttpStatus.OK, "[]", "[]", calls).inspect(PRODUCT_ID).block();
        assertNotNull(audit);
        assertEquals("PUBLISH_PENDING", audit.status());
        assertEquals(0, calls.get());
    }

    @Test
    void unavailableConsumerMustNotBeCountedAsMissingState() {
        AtomicInteger calls = new AtomicInteger();
        var audit = service(List.of(accepted("prod-event-e", PRODUCT, "CREATE", 0)),
                HttpStatus.SERVICE_UNAVAILABLE, "[]", "[]", calls).inspect(PRODUCT_ID).block();
        assertNotNull(audit);
        assertEquals("INCONCLUSIVE", audit.status());
        assertEquals("UNAVAILABLE", audit.checks().get(0).observation());
    }

    @Test
    void publishedDeleteCanMatchMissingProduct() {
        AtomicInteger calls = new AtomicInteger();
        var audit = service(List.of(accepted("prod-event-f", PRODUCT, "DELETE", 0)),
                HttpStatus.NOT_FOUND, "[]", "[]", calls).inspect(PRODUCT_ID).block();
        assertNotNull(audit);
        assertEquals("OBSERVED", audit.status());
        assertEquals("MATCHED", audit.checks().get(0).observation());
    }

    @Test
    void truncationFailsClosedWithoutInspectingMisleadingPartialHistory() {
        List<OutboxEntry> history = new ArrayList<>();
        for (int i = 0; i < 101; i++)
            history.add(accepted("evt-" + i, PRODUCT, "CREATE", 0));
        AtomicInteger calls = new AtomicInteger();
        var audit = service(history, HttpStatus.OK, "[]", "[]", calls)
                .inspect(PRODUCT_ID).block();
        assertNotNull(audit);
        assertEquals("INCOMPLETE_HISTORY", audit.status());
        assertTrue(audit.truncated());
        assertEquals(0, calls.get());
    }

    @Test
    void malformedJournalIsInconclusiveAndCannotTriggerRepair() {
        OutboxEntry bad = accepted("bad-event", PRODUCT, "CREATE", 0);
        bad.setPayloadJson("not-json");
        AtomicInteger calls = new AtomicInteger();
        var audit = service(List.of(bad), HttpStatus.OK, "[]", "[]", calls)
                .inspect(PRODUCT_ID).block();
        assertNotNull(audit);
        assertEquals("INVALID_JOURNAL", audit.status());
        assertEquals(0, calls.get());
    }

    @Test
    void unknownProductHasNoHistoryRatherThanInventedConsistency() {
        AtomicInteger calls = new AtomicInteger();
        var audit = service(List.of(), HttpStatus.OK, "[]", "[]", calls)
                .inspect(PRODUCT_ID).block();
        assertNotNull(audit);
        assertEquals("NOT_TRACKED", audit.status());
        assertEquals(0, calls.get());
    }
}
