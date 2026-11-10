package amazon.microservices.composite.product.outbox;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

/** Immutable operator request for one read-only receipt recheck. */
@Document("saga_recheck_requests")
@CompoundIndex(name="rechecks_by_operation_idx",
        def="{'operationId':1,'createdAt':-1,'_id':-1}")
public class SagaRecheckRequest {
    @Id private String id;
    private String operationId;
    private String actorFingerprint;
    private String reason;
    private int sequence;
    private Instant createdAt;

    public SagaRecheckRequest() {}

    public SagaRecheckRequest(String id, String operationId, String actorFingerprint,
                              String reason, int sequence, Instant createdAt) {
        this.id=id;
        this.operationId=operationId;
        this.actorFingerprint=actorFingerprint;
        this.reason=reason;
        this.sequence=sequence;
        this.createdAt=createdAt;
    }

    public String getId(){return id;}
    public String getOperationId(){return operationId;}
    public String getActorFingerprint(){return actorFingerprint;}
    public String getReason(){return reason;}
    public int getSequence(){return sequence;}
    public Instant getCreatedAt(){return createdAt;}
    public void setId(String v){id=v;}
    public void setOperationId(String v){operationId=v;}
    public void setActorFingerprint(String v){actorFingerprint=v;}
    public void setReason(String v){reason=v;}
    public void setSequence(int v){sequence=v;}
    public void setCreatedAt(Instant v){createdAt=v;}
}
