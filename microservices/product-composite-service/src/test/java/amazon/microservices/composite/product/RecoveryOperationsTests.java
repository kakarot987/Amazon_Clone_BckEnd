package amazon.microservices.composite.product;

import amazon.api.core.product.Product;
import amazon.api.event.Event;
import amazon.microservices.composite.product.outbox.AggregateOperation;
import amazon.microservices.composite.product.outbox.OutboxEntry;
import amazon.microservices.composite.product.outbox.RecoveryOperationsDashboard;
import amazon.microservices.composite.product.outbox.SagaRecheckRequest;
import amazon.microservices.composite.product.outbox.SagaRecheckService;
import amazon.microservices.composite.product.outbox.SagaSupervision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Testcontainers Mongo replica-set tests for read-only recheck operations. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes={TestSecurityConfig.class},
        properties={"eureka.client.enabled=false","spring.cloud.config.enabled=false",
                "spring.cloud.compatibility-verifier.enabled=false",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
                "spring.main.allow-bean-definition-overriding=true",
                "spring.cloud.stream.defaultBinder=rabbit",
                "app.outbox.initial-delay-ms=600000", "app.saga.enabled=false"})
@Import(TestChannelBinderConfiguration.class)
class RecoveryOperationsTests extends OutboxMongoTestBase {
    @Autowired private SagaRecheckService recheck;
    @Autowired private RecoveryOperationsDashboard dashboard;
    @Autowired private ReactiveMongoTemplate mongo;

    @BeforeEach
    void clear() {
        mongo.remove(new Query(),SagaRecheckRequest.class).block();
        mongo.remove(new Query(),SagaSupervision.class).block();
        mongo.remove(new Query(),OutboxEntry.class).block();
        mongo.remove(new Query(),AggregateOperation.class).block();
    }

    private static Jwt operator(String subject) {
        return Jwt.withTokenValue("test-only")
                .header("alg","none")
                .claim("iss","https://issuer.example")
                .subject(subject)
                .build();
    }

    private String seed(String state) {
        String id=UUID.randomUUID().toString();
        SagaSupervision item=new SagaSupervision(id,Instant.now());
        item.setState(state);
        item.setChecks(12);
        item.setGeneration(7L);
        item.setReviewReason("CHECK_BUDGET_EXHAUSTED");
        mongo.insert(item).block();
        return id;
    }

    private SagaRecheckService.RecheckCommand reason(String value) {
        return new SagaRecheckService.RecheckCommand(value);
    }

    @Test
    void recheckIsAtomicallyRecordedAndRequeuedWithoutTouchingBusinessOutbox() {
        String id=seed(SagaSupervision.NEEDS_REVIEW);
        SagaRecheckRequest result=recheck.request(id,"recheck-ticket-0001",
                reason("CONSUMER_RESTORED"),operator("operator-a")).block();
        assertNotNull(result);
        assertEquals(id,result.getOperationId());
        assertEquals(1,result.getSequence());
        assertNotEquals("operator-a",result.getActorFingerprint());
        SagaSupervision next=mongo.findById(id,SagaSupervision.class).block();
        assertNotNull(next);
        assertEquals(SagaSupervision.PENDING,next.getState());
        assertEquals(0,next.getChecks());
        assertEquals(8,next.getGeneration());
        assertNull(next.getReviewReason());
        assertEquals(1L,mongo.count(new Query(),SagaRecheckRequest.class).block());
        assertEquals(0L,mongo.count(new Query(),OutboxEntry.class).block());
        assertEquals(1,recheck.history(id,10).block().size());
    }

    @Test
    void sameKeyIsIdempotentEvenWhenSupervisionIsNoLongerNeedsReview() {
        String id=seed(SagaSupervision.NEEDS_REVIEW);
        SagaRecheckRequest first=recheck.request(id,"recheck-ticket-0002",
                reason("EVIDENCE_UPDATED"),operator("operator-a")).block();
        SagaRecheckRequest again=recheck.request(id,"recheck-ticket-0002",
                reason("EVIDENCE_UPDATED"),operator("operator-a")).block();
        assertNotNull(first);
        assertNotNull(again);
        assertEquals(first.getId(),again.getId());
        assertEquals(first.getCreatedAt(),again.getCreatedAt());
        assertEquals(1L,mongo.count(new Query(),SagaRecheckRequest.class).block());
        assertEquals(8L,mongo.findById(id,SagaSupervision.class).block().getGeneration());
    }

    @Test
    void changedReasonOrActorWithOriginalKeyIsRejected() {
        String id=seed(SagaSupervision.NEEDS_REVIEW);
        recheck.request(id,"recheck-ticket-0003",reason("BROKER_RECOVERED"),
                operator("operator-a")).block();
        assertThrows(ResponseStatusException.class, () ->
                recheck.request(id,"recheck-ticket-0003",reason("OPERATOR_REVIEW"),
                        operator("operator-a")).block());
        assertThrows(ResponseStatusException.class, () ->
                recheck.request(id,"recheck-ticket-0003",reason("BROKER_RECOVERED"),
                        operator("operator-b")).block());
        assertEquals(1L,mongo.count(new Query(),SagaRecheckRequest.class).block());
    }

    @Test
    void onlyFlaggedCasesCanRecheckAndInvalidInputDoesNotCreateIntent() {
        String id=seed(SagaSupervision.WAITING);
        assertThrows(ResponseStatusException.class, () ->
                recheck.request(id,"recheck-ticket-0004",reason("OPERATOR_REVIEW"),
                        operator("operator-a")).block());
        assertThrows(ResponseStatusException.class, () ->
                recheck.request(id,"short",reason("OPERATOR_REVIEW"),
                        operator("operator-a")).block());
        assertThrows(ResponseStatusException.class, () ->
                recheck.request(id,"recheck-ticket-0004",reason("WRONG_REASON"),
                        operator("operator-a")).block());
        assertThrows(ResponseStatusException.class, () ->
                recheck.request(UUID.randomUUID().toString(),"recheck-ticket-0004",
                        reason("OPERATOR_REVIEW"),operator("operator-a")).block());
        assertEquals(0L,mongo.count(new Query(),SagaRecheckRequest.class).block());
    }

    @Test
    void threeManualRecheckRequestsPerOperationAtMost() {
        String id=seed(SagaSupervision.NEEDS_REVIEW);
        for(int i=1;i<=3;i++) {
            recheck.request(id,"recheck-budget-key-0"+i,
                    reason("OPERATOR_REVIEW"),operator("operator-a")).block();
            // Simulates the external supervisor having completed another check budget.
            mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                    new Update().set("state",SagaSupervision.NEEDS_REVIEW)
                            .set("reviewReason","CHECK_BUDGET_EXHAUSTED"),
                    SagaSupervision.class).block();
        }
        assertThrows(ResponseStatusException.class, () ->
                recheck.request(id,"recheck-budget-key-04",
                        reason("OPERATOR_REVIEW"),operator("operator-a")).block());
        assertEquals(3L,mongo.count(new Query(),SagaRecheckRequest.class).block());
    }

    @Test
    void recoveryDashboardShowsHeadOfLineBackoffWithoutLeakingPayload() {
        String id=seed(SagaSupervision.NEEDS_REVIEW);
        OutboxEntry outbox=new OutboxEntry("outbox-event-for-dashboard",
                "products-out-0","private-event-payload",901,Instant.now().minusSeconds(10));
        outbox.setNextAttemptAt(Instant.now().plusSeconds(120));
        mongo.insert(outbox).block();
        var status=dashboard.get().block();
        assertNotNull(status);
        assertEquals(1L,status.needsReview());
        assertNotNull(status.oldestUndelivered());
        assertEquals("RETRY_BACKOFF",status.oldestUndelivered().readiness());
        assertEquals(outbox.getId(),status.oldestUndelivered().eventId());
        assertFalse(status.toString().contains("private-event-payload"));
    }

    @Test
    void dashboardShowsEmptyOutboxWithoutInventingBacklog() {
        seed(SagaSupervision.WAITING);
        var status=dashboard.get().block();
        assertNotNull(status);
        assertEquals(1L,status.waiting());
        assertNull(status.oldestUndelivered());
    }
}
