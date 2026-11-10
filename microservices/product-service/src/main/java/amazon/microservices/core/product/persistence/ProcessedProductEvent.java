package amazon.microservices.core.product.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

/** Atomic inbox receipt for product events in the same database as the catalog. */
@Document("processed_product_events")
public class ProcessedProductEvent {
    @Id private String id;
    private String fingerprint;
    private Instant processedAt;
    public ProcessedProductEvent() {}
    public ProcessedProductEvent(String id, String fingerprint) {
        this.id = id; this.fingerprint = fingerprint; this.processedAt = Instant.now();
    }
    public String getId(){return id;}
    public String getFingerprint(){return fingerprint;}
    public Instant getProcessedAt(){return processedAt;}
    public void setId(String id){this.id=id;}
    public void setFingerprint(String value){fingerprint=value;}
    public void setProcessedAt(Instant value){processedAt=value;}
}
