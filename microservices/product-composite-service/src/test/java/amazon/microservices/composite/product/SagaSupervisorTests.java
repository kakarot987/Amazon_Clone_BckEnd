package amazon.microservices.composite.product;

import amazon.microservices.composite.product.outbox.AggregateOperation;
import amazon.microservices.composite.product.outbox.ConsumerReceiptAuditService;
import amazon.microservices.composite.product.outbox.OutboxEntry;
import amazon.microservices.composite.product.outbox.SagaSupervision;
import amazon.microservices.composite.product.outbox.SagaSupervisor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes={TestSecurityConfig.class},
        properties={"eureka.client.enabled=false","spring.cloud.config.enabled=false",
                "spring.cloud.compatibility-verifier.enabled=false",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
                "spring.main.allow-bean-definition-overriding=true",
                "spring.cloud.stream.defaultBinder=rabbit",
                "app.outbox.initial-delay-ms=600000",
                "app.saga.enabled=true","app.saga.initial-delay-ms=600000"})
@Import(TestChannelBinderConfiguration.class)
class SagaSupervisorTests extends OutboxMongoTestBase {
    @Autowired private SagaSupervisor supervisor;
    @Autowired private ReactiveMongoTemplate mongo;
    @MockBean private ConsumerReceiptAuditService audit;

    @BeforeEach
    void reset() {
        mongo.remove(new Query(), SagaSupervision.class).block();
        mongo.remove(new Query(), OutboxEntry.class).block();
        mongo.remove(new Query(), AggregateOperation.class).block();
        reset(audit);
    }

    private String seed() {
        String id=UUID.randomUUID().toString();
        mongo.insert(new SagaSupervision(id, Instant.now().minusSeconds(2))).block();
        return id;
    }

    private SagaSupervision current(String id) {
        return mongo.findById(id, SagaSupervision.class).block();
    }

    private ConsumerReceiptAuditService.Audit evidence(String id, String status, int expected, int verified) {
        return new ConsumerReceiptAuditService.Audit(
                id, status, Instant.now(), expected, verified, List.of());
    }

    @Test
    void allReceiptsVerifiedFinalizesOnlyDiagnosticMetadata() {
        String id=seed();
        when(audit.inspect(id)).thenReturn(Mono.just(evidence(id,"ALL_RECEIPTS_VERIFIED",2,2)));
        supervisor.runPass().block();

        SagaSupervision row=current(id);
        assertNotNull(row);
        assertEquals(SagaSupervision.VERIFIED,row.getState());
        assertEquals("ALL_RECEIPTS_VERIFIED",row.getLastAuditStatus());
        assertEquals(1,row.getChecks());
        assertEquals(2,row.getVerifiedEvents());
        assertNull(row.getLeaseId());
        assertNull(row.getNextCheckAt());
        supervisor.runPass().block();
        verify(audit,times(1)).inspect(id);
    }

    @Test
    void inconclusiveChecksBackOffWithoutMarkingSuccess() {
        String id=seed();
        when(audit.inspect(id)).thenReturn(Mono.just(evidence(id,"INCONCLUSIVE",0,0)));
        supervisor.runPass().block();

        SagaSupervision row=current(id);
        assertNotNull(row);
        assertEquals(SagaSupervision.WAITING,row.getState());
        assertEquals("INCONCLUSIVE",row.getLastAuditStatus());
        assertTrue(row.getNextCheckAt().isAfter(Instant.now()));
        assertEquals(1,row.getChecks());
        supervisor.runPass().block();
        verify(audit,times(1)).inspect(id);
    }

    @Test
    void explicitConflictQuarantinesWithoutAnyOutboxMutation() {
        String id=seed();
        when(audit.inspect(id)).thenReturn(Mono.just(evidence(id,"CONFLICT",2,0)));
        supervisor.runPass().block();
        SagaSupervision row=current(id);
        assertNotNull(row);
        assertEquals(SagaSupervision.NEEDS_REVIEW,row.getState());
        assertEquals("CONFLICT",row.getReviewReason());
        assertNull(row.getNextCheckAt());
        verify(audit,times(1)).inspect(id);
    }

    @Test
    void staleWorkerCannotOverwriteNewerLease() {
        String id=seed();
        Sinks.One<ConsumerReceiptAuditService.Audit> pending=Sinks.one();
        when(audit.inspect(id)).thenReturn(pending.asMono());
        var running=supervisor.runPass().subscribe();
        try {
            SagaSupervision claimed=null;
            for(int i=0;i<100;i++) {
                claimed=current(id);
                if(claimed!=null && SagaSupervision.CHECKING.equals(claimed.getState())) break;
                Thread.sleep(20);
            }
            assertNotNull(claimed);
            assertEquals(SagaSupervision.CHECKING,claimed.getState());
            long generation=claimed.getGeneration();
            mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                    new Update().set("leaseId","newer-worker-claim")
                            .set("leaseUntil",Instant.now().plusSeconds(60))
                            .inc("generation",1),SagaSupervision.class).block();

            pending.tryEmitValue(evidence(id,"ALL_RECEIPTS_VERIFIED",1,1));
            for(int i=0;i<100;i++) {
                Thread.sleep(20);
                if(current(id).getState().equals(SagaSupervision.VERIFIED)) break;
            }
            SagaSupervision after=current(id);
            assertNotNull(after);
            assertEquals(SagaSupervision.CHECKING,after.getState());
            assertEquals("newer-worker-claim",after.getLeaseId());
            assertEquals(generation+1,after.getGeneration());
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail(ex);
        } finally {
            running.dispose();
        }
    }

    @Test
    void exhaustedCheckBudgetRequiresOperatorReview() {
        String id=seed();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("checks",11),SagaSupervision.class).block();
        when(audit.inspect(id)).thenReturn(Mono.just(evidence(id,"RECEIPTS_MISSING",1,0)));
        supervisor.runPass().block();
        SagaSupervision row=current(id);
        assertNotNull(row);
        assertEquals(12,row.getChecks());
        assertEquals(SagaSupervision.NEEDS_REVIEW,row.getState());
        assertEquals("CHECK_BUDGET_EXHAUSTED",row.getReviewReason());
    }
}
