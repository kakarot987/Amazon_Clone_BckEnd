package amazon.microservices.core.review.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

/** Separate from legacy event-created reviews; one per verified user and product. */
@Entity
@Table(name = "customer_reviews",
    uniqueConstraints = @UniqueConstraint(name = "customer_review_owner_unique",
        columnNames = {"product_id", "owner_key"}),
    indexes = @Index(name = "customer_reviews_by_product", columnList = "product_id"))
public class CustomerReviewEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(name = "product_id", nullable = false)
    private int productId;

    @Column(name = "owner_key", nullable = false, length = 64)
    private String ownerKey;

    @Column(nullable = false)
    private int rating;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 2000)
    private String content;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public CustomerReviewEntity() {}

    public CustomerReviewEntity(int productId, String ownerKey, int rating,
                                String title, String content) {
        this.productId = productId;
        this.ownerKey = ownerKey;
        this.rating = rating;
        this.title = title;
        this.content = content;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public Long getVersion() { return version; }
    public int getProductId() { return productId; }
    public String getOwnerKey() { return ownerKey; }
    public int getRating() { return rating; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setVersion(Long version) { this.version = version; }
    public void setProductId(int productId) { this.productId = productId; }
    public void setOwnerKey(String ownerKey) { this.ownerKey = ownerKey; }
    public void setRating(int rating) { this.rating = rating; }
    public void setTitle(String title) { this.title = title; }
    public void setContent(String content) { this.content = content; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
