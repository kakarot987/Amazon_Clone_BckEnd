package amazon.microservices.composite.product.outbox;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Immutable operator approval to retry the ORIGINAL immutable outbox event.
 * Never a request to create new event IDs or mutate downstream business state.
 */
@Document("outbox_redrive_requests")
@CompoundIndex(name = "redrive_by_event_idx",
        def = "{'eventId':1,'createdAt':-1,'_id':-1}")
public class OutboxRedriveRequest {
    @Id private String id;
    private String eventId;
    private String actorFingerprint;
    private String reason;
    private String originalPayloadFingerprint;
    private int sequence;
    private Instant createdAt;

    public OutboxRedriveRequest() {}

    public OutboxRedriveRequest(String id, String eventId, String actorFingerprint,
                               String reason, String originalPayloadFingerprint,
                               int sequence, Instant createdAt) {
        this.id=id; this.eventId=eventId; this.actorFingerprint=actorFingerprint;
        this.reason=reason; this.originalPayloadFingerprint=originalPayloadFingerprint;
        this.sequence=sequence; this.createdAt=createdAt;
    }
    public String getId(){return id;}
    public String getEventId(){return eventId;}
    public String getActorFingerprint(){return actorFingerprint;}
    public String getReason(){return reason;}
    public String getOriginalPayloadFingerprint(){return originalPayloadFingerprint;}
    public int getSequence(){return sequence;}
    public Instant getCreatedAt(){return createdAt;}
    public void setId(String v){id=v;}
    public void setEventId(String v){eventId=v;}
    public void setActorFingerprint(String v){actorFingerprint=v;}
    public void setReason(String v){reason=v;}
    public void setOriginalPayloadFingerprint(String v){originalPayloadFingerprint=v;}
    public void setSequence(int v){sequence=v;}
    public void setCreatedAt(Instant v){createdAt=v;}
}
