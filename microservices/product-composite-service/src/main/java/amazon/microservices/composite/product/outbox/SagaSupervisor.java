package amazon.microservices.composite.product.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bounded, fenced, opt-in receipt supervision. Writes metadata only:
 * never republishes, releases stock, refunds or compensates events.
 */
@Component
@ConditionalOnProperty(prefix="app.saga", name="enabled", havingValue="true")
public class SagaSupervisor {
    private static final Logger LOG = LoggerFactory.getLogger(SagaSupervisor.class);
    private static final int MAX_PER_PASS=10;
    private static final int MAX_CHECKS=12;
    private static final Duration LEASE=Duration.ofSeconds(90);
    private static final Duration AUDIT_TIMEOUT=Duration.ofSeconds(20);

    private final ReactiveMongoTemplate mongo;
    private final ConsumerReceiptAuditService audit;

    public SagaSupervisor(ReactiveMongoTemplate mongo, ConsumerReceiptAuditService audit) {
        this.mongo=mongo; this.audit=audit;
    }

    @Scheduled(initialDelayString="${app.saga.initial-delay-ms:10000}",
            fixedDelayString="${app.saga.poll-delay-ms:5000}")
    public void poll() {
        runPass().subscribe(ignored -> {},
                error -> LOG.warn("Saga supervisor poll failed: {}",
                        error.getClass().getSimpleName()));
    }

    public Mono<Void> runPass() {
        return Flux.range(0,MAX_PER_PASS)
                .concatMap(i -> checkOne())
                .takeUntil(processed -> !processed).then();
    }

    private Mono<Boolean> checkOne() {
        Instant now=Instant.now();
        Criteria due=new Criteria().orOperator(
                new Criteria().andOperator(
                        Criteria.where("state").in(List.of(SagaSupervision.PENDING,SagaSupervision.WAITING)),
                        Criteria.where("nextCheckAt").lte(now)),
                new Criteria().andOperator(
                        Criteria.where("state").is(SagaSupervision.CHECKING),
                        Criteria.where("leaseUntil").lte(now)));
        Query query=Query.query(due)
                .with(Sort.by(Sort.Order.asc("createdAt"),Sort.Order.asc("_id")));
        String claim=UUID.randomUUID().toString();
        Update update=new Update().set("state",SagaSupervision.CHECKING)
                .set("leaseId",claim).set("leaseUntil",now.plus(LEASE))
                .inc("generation",1).inc("checks",1);
        return mongo.findAndModify(query,update,
                    FindAndModifyOptions.options().returnNew(true),SagaSupervision.class)
                .flatMap(owned -> audit.inspect(owned.getId())
                        .timeout(AUDIT_TIMEOUT)
                        .onErrorResume(error -> {
                            LOG.warn("Receipt inspection inconclusive: {}",
                                    error.getClass().getSimpleName());
                            return Mono.just(new ConsumerReceiptAuditService.Audit(
                                    owned.getId(),"INCONCLUSIVE",Instant.now(),0,0,List.of()));
                        })
                        .flatMap(result -> settle(owned,claim,result))
                        .thenReturn(true))
                .defaultIfEmpty(false);
    }

    private Mono<Void> settle(SagaSupervision owned,String claim,
                              ConsumerReceiptAuditService.Audit evidence) {
        Instant now=Instant.now();
        String outcome=evidence!=null && owned.getId().equals(evidence.operationId())
                ? evidence.status() : "INCONCLUSIVE";
        int expected=evidence==null?0:evidence.expectedEvents();
        int verified=evidence==null?0:evidence.verifiedEvents();
        boolean allVerified="ALL_RECEIPTS_VERIFIED".equals(outcome)
                && expected>0 && verified==expected;
        boolean dangerous="CONFLICT".equals(outcome) || "INCOMPLETE_JOURNAL".equals(outcome);
        String state=allVerified?SagaSupervision.VERIFIED
                : dangerous || owned.getChecks()>=MAX_CHECKS
                    ?SagaSupervision.NEEDS_REVIEW:SagaSupervision.WAITING;
        String reason=dangerous?outcome
                : SagaSupervision.NEEDS_REVIEW.equals(state)?"CHECK_BUDGET_EXHAUSTED":null;
        long delay=Math.min(300L,15L*(1L<<Math.min(5,owned.getChecks()-1)));
        Update update=new Update().set("state",state)
                .set("lastCheckedAt",now).set("lastAuditStatus",outcome)
                .set("expectedEvents",expected).set("verifiedEvents",verified)
                .unset("leaseId").unset("leaseUntil");
        if(SagaSupervision.WAITING.equals(state))
            update.set("nextCheckAt",now.plusSeconds(delay));
        else update.unset("nextCheckAt");
        if(reason!=null)update.set("reviewReason",reason);
        else update.unset("reviewReason");

        // A worker whose lease expired cannot overwrite a newer worker's result.
        Query fenced=Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(owned.getId()),
                Criteria.where("state").is(SagaSupervision.CHECKING),
                Criteria.where("leaseId").is(claim),
                Criteria.where("generation").is(owned.getGeneration()),
                Criteria.where("leaseUntil").gt(now)));
        return mongo.updateFirst(fenced,update,SagaSupervision.class)
                .doOnNext(result -> {
                    if(result.getModifiedCount()==0)
                        LOG.info("Ignored expired or superseded supervisor claim");
                }).then();
    }
}
