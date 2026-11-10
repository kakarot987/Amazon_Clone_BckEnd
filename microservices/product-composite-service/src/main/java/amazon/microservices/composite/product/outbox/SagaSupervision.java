package amazon.microservices.composite.product.outbox;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

/** Durable read-only supervision metadata; never authorizes business mutations. */
@Document("aggregate_supervisions")
@CompoundIndex(name="saga_supervision_due_idx", def="{'state':1,'nextCheckAt':1,'leaseUntil':1,'createdAt':1}")
public class SagaSupervision {
    public static final String PENDING = "PENDING";
    public static final String CHECKING = "CHECKING";
    public static final String WAITING = "WAITING";
    public static final String VERIFIED = "RECEIPTS_VERIFIED";
    public static final String NEEDS_REVIEW = "NEEDS_REVIEW";

    @Id private String id;
    private Instant createdAt;
    private String state;
    private Instant nextCheckAt;
    private String leaseId;
    private Instant leaseUntil;
    private long generation;
    private int checks;
    private Instant lastCheckedAt;
    private String lastAuditStatus;
    private int expectedEvents;
    private int verifiedEvents;
    private String reviewReason;

    public SagaSupervision() {}
    public SagaSupervision(String operationId, Instant createdAt) {
        this.id=operationId; this.createdAt=createdAt;
        this.nextCheckAt=createdAt; this.state=PENDING;
    }
    public String getId(){return id;}
    public Instant getCreatedAt(){return createdAt;}
    public String getState(){return state;}
    public Instant getNextCheckAt(){return nextCheckAt;}
    public String getLeaseId(){return leaseId;}
    public Instant getLeaseUntil(){return leaseUntil;}
    public long getGeneration(){return generation;}
    public int getChecks(){return checks;}
    public Instant getLastCheckedAt(){return lastCheckedAt;}
    public String getLastAuditStatus(){return lastAuditStatus;}
    public int getExpectedEvents(){return expectedEvents;}
    public int getVerifiedEvents(){return verifiedEvents;}
    public String getReviewReason(){return reviewReason;}
    public void setId(String v){id=v;}
    public void setCreatedAt(Instant v){createdAt=v;}
    public void setState(String v){state=v;}
    public void setNextCheckAt(Instant v){nextCheckAt=v;}
    public void setLeaseId(String v){leaseId=v;}
    public void setLeaseUntil(Instant v){leaseUntil=v;}
    public void setGeneration(long v){generation=v;}
    public void setChecks(int v){checks=v;}
    public void setLastCheckedAt(Instant v){lastCheckedAt=v;}
    public void setLastAuditStatus(String v){lastAuditStatus=v;}
    public void setExpectedEvents(int v){expectedEvents=v;}
    public void setVerifiedEvents(int v){verifiedEvents=v;}
    public void setReviewReason(String v){reviewReason=v;}
}
