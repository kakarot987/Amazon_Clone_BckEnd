package amazon.microservices.composite.product.outbox;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

/**
 * Append-only operator investigation note. Never represents saga completion
 * or permission to perform any business mutation.
 */
@Document("saga_triage_notes")
@CompoundIndex(name="triage_by_operation_idx",
        def="{'operationId':1,'createdAt':-1,'_id':-1}")
public class SagaTriageNote {
    @Id private String id;
    private String operationId;
    private String actorFingerprint;
    private String category;
    private String note;
    private Instant createdAt;

    public SagaTriageNote() {}

    public SagaTriageNote(String id, String operationId, String actorFingerprint,
                          String category, String note, Instant createdAt) {
        this.id=id; this.operationId=operationId; this.actorFingerprint=actorFingerprint;
        this.category=category; this.note=note; this.createdAt=createdAt;
    }

    public String getId(){return id;}
    public String getOperationId(){return operationId;}
    public String getActorFingerprint(){return actorFingerprint;}
    public String getCategory(){return category;}
    public String getNote(){return note;}
    public Instant getCreatedAt(){return createdAt;}

    public void setId(String v){id=v;}
    public void setOperationId(String v){operationId=v;}
    public void setActorFingerprint(String v){actorFingerprint=v;}
    public void setCategory(String v){category=v;}
    public void setNote(String v){note=v;}
    public void setCreatedAt(Instant v){createdAt=v;}
}
