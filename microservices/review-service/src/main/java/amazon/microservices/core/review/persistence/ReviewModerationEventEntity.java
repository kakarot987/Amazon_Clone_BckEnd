package amazon.microservices.core.review.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/** Immutable, uniquely identified operator moderation action. */
@Entity
@Table(name = "review_moderation_events")
public class ReviewModerationEventEntity {
    @Id
    @Column(length = 100)
    private String eventId;
    @Column(nullable = false)
    private Long reviewId;
    @Column(nullable = false, length = 12)
    private String action;
    @Column(nullable = false, length = 24)
    private String reasonCode;
    @Column(nullable = false)
    private Instant createdAt;
    public ReviewModerationEventEntity() {}
    public ReviewModerationEventEntity(String id, Long reviewId, String action, String reason) {
        this.eventId=id; this.reviewId=reviewId; this.action=action; this.reasonCode=reason;
        this.createdAt=Instant.now();
    }
    public String getEventId(){return eventId;}
    public Long getReviewId(){return reviewId;}
    public String getAction(){return action;}
    public String getReasonCode(){return reasonCode;}
    public Instant getCreatedAt(){return createdAt;}
    public void setEventId(String v){eventId=v;}
    public void setReviewId(Long v){reviewId=v;}
    public void setAction(String v){action=v;}
    public void setReasonCode(String v){reasonCode=v;}
    public void setCreatedAt(Instant v){createdAt=v;}
}
