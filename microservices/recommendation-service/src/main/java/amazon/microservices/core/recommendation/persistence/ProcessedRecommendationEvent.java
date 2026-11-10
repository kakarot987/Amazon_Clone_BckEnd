package amazon.microservices.core.recommendation.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

/** Durable inbox receipt, committed with the matching recommendation mutation. */
@Document("processed_recommendation_events")
public class ProcessedRecommendationEvent {
    @Id private String id;
    private String fingerprint;
    private Instant processedAt;
    public ProcessedRecommendationEvent() {}
    public ProcessedRecommendationEvent(String id, String fingerprint) {
        this.id=id; this.fingerprint=fingerprint; this.processedAt=Instant.now();
    }
    public String getId(){return id;}
    public String getFingerprint(){return fingerprint;}
    public Instant getProcessedAt(){return processedAt;}
    public void setId(String id){this.id=id;}
    public void setFingerprint(String value){fingerprint=value;}
    public void setProcessedAt(Instant value){processedAt=value;}
}
