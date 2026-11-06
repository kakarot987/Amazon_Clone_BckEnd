package amazon.microservices.core.product.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import java.math.BigDecimal;
import java.time.Instant;

/** Durable checkout intent, no payment is charged. */
@Document(collection = "orders")
public class OrderEntity {
    public static final String PENDING = "PENDING";
    public static final String RESERVED = "RESERVED";
    public static final String CANCELLATION_PENDING = "CANCELLATION_PENDING";
    public static final String CANCELLED = "CANCELLED";
    @Id private String id;
    @Version private Long version;
    private String ownerKey;
    private int productId;
    private int quantity;
    private String productName;
    private BigDecimal unitPrice;
    private String currency;
    private String status;
    private Instant createdAt;
    public OrderEntity() {}
    public OrderEntity(String id, String ownerKey, int productId, int quantity,
                       String name, BigDecimal price, String currency) {
        this.id = id;
        this.ownerKey = ownerKey;
        this.productId = productId;
        this.quantity = quantity;
        this.productName = name;
        this.unitPrice = price;
        this.currency = currency;
        this.status = PENDING;
        this.createdAt = Instant.now();
    }
    public String getId() { return id; }
    public Long getVersion() { return version; }
    public String getOwnerKey() { return ownerKey; }
    public int getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public String getProductName() { return productName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setId(String id) { this.id = id; }
    public void setVersion(Long version) { this.version = version; }
    public void setOwnerKey(String ownerKey) { this.ownerKey = ownerKey; }
    public void setProductId(int productId) { this.productId = productId; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public void setProductName(String name) { this.productName = name; }
    public void setUnitPrice(BigDecimal price) { this.unitPrice = price; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setStatus(String status) { this.status = status; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
