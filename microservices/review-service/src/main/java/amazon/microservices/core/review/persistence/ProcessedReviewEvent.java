package amazon.microservices.core.review.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/** SQL inbox row committed with the legacy review mutation. */
@Entity
@Table(name = "processed_review_events")
public class ProcessedReviewEvent {
    @Id @Column(length=100)
    private String id;
    @Column(nullable=false,length=64)
    private String fingerprint;
    @Column(nullable=false)
    private Instant processedAt;
    public ProcessedReviewEvent() {}
    public ProcessedReviewEvent(String id, String fingerprint) {
        this.id=id; this.fingerprint=fingerprint; this.processedAt=Instant.now();
    }
    public String getId(){return id;}
    public String getFingerprint(){return fingerprint;}
    public Instant getProcessedAt(){return processedAt;}
    public void setId(String value){id=value;}
    public void setFingerprint(String value){fingerprint=value;}
    public void setProcessedAt(Instant value){processedAt=value;}
}
