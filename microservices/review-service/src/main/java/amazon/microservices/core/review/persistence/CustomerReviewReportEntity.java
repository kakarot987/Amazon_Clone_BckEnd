package amazon.microservices.core.review.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/** A customer can flag each review at most once. */
@Entity
@Table(name = "customer_review_reports",
       uniqueConstraints = @UniqueConstraint(name = "review_report_once",
                       columnNames = {"review_id", "reporter_key"}),
       indexes = @Index(name = "review_reports_by_review", columnList = "review_id"))
public class CustomerReviewReportEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "review_id", nullable = false)
    private Long reviewId;
    @Column(name = "product_id", nullable = false)
    private int productId;
    @Column(name = "reporter_key", nullable = false, length = 64)
    private String reporterKey;
    @Column(nullable = false, length = 24)
    private String reasonCode;
    @Column(nullable = false)
    private Instant createdAt;
    public CustomerReviewReportEntity() {}
    public CustomerReviewReportEntity(Long reviewId, int productId, String reporterKey, String reasonCode) {
        this.reviewId=reviewId; this.productId=productId; this.reporterKey=reporterKey;
        this.reasonCode=reasonCode; this.createdAt=Instant.now();
    }
    public Long getId(){return id;}
    public Long getReviewId(){return reviewId;}
    public int getProductId(){return productId;}
    public String getReporterKey(){return reporterKey;}
    public String getReasonCode(){return reasonCode;}
    public Instant getCreatedAt(){return createdAt;}
    public void setId(Long v){id=v;}
    public void setReviewId(Long v){reviewId=v;}
    public void setProductId(int v){productId=v;}
    public void setReporterKey(String v){reporterKey=v;}
    public void setReasonCode(String v){reasonCode=v;}
    public void setCreatedAt(Instant v){createdAt=v;}
}
