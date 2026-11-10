package amazon.microservices.core.product.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import java.math.BigDecimal;
import amazon.api.commerce.OrderLine;
import java.util.List;
import java.util.ArrayList;
import java.time.Instant;
import java.time.Duration;

/** Durable checkout intent, no payment is charged. */
@Document(collection = "orders")
public class OrderEntity {
    public static final String PENDING = "PENDING";
    public static final String RESERVING = "RESERVING";
    public static final String RESERVED = "RESERVED";
    public static final String CANCELLATION_PENDING = "CANCELLATION_PENDING";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRATION_PENDING = "EXPIRATION_PENDING";
    public static final String EXPIRED = "EXPIRED";
    public static final String PAYMENT_PENDING = "PAYMENT_PENDING";
    public static final String PAYMENT_CONFIRMING = "PAYMENT_CONFIRMING";
    public static final String MOCK_PAID = "MOCK_PAID";
    public static final String PAYMENT_FAILED_PENDING = "PAYMENT_FAILED_PENDING";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    @Id private String id;
    @Version private Long version;
    private String ownerKey;
    private int productId;
    private int quantity;
    private String productName;
    private BigDecimal unitPrice;
    // Snapshot of every checkout item; older orders use the legacy scalar columns.
    private List<OrderLine> items;
    private String currency;
    private String status;
    private Instant createdAt;
    private Instant expiresAt;
    // Durable processing claim; separate from product-level reservation tokens.
    private String reservationLeaseId;
    private Instant reservationLeaseUntil;
    // The order document is the authoritative mock-payment attempt record.
    private String paymentStatus;
    private String paymentEventId;
    private Instant paymentStartedAt;
    private Instant mockPaidAt;
    public OrderEntity() {}
    public OrderEntity(String id, String ownerKey, int productId, int quantity,
                       String name, BigDecimal price, String currency) {
        this(id, ownerKey, productId, quantity, name, price, currency,
                Instant.now().plus(Duration.ofMinutes(15)));
    }
    public OrderEntity(String id, String ownerKey, int productId, int quantity,
                       String name, BigDecimal price, String currency, Instant expiresAt) {
        this.id = id;
        this.ownerKey = ownerKey;
        this.productId = productId;
        this.quantity = quantity;
        this.productName = name;
        this.unitPrice = price;
        this.items = new ArrayList<>(List.of(new OrderLine(productId, quantity, name, price)));
        this.currency = currency;
        this.status = PENDING;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }
    public String getId() { return id; }
    public Long getVersion() { return version; }
    public String getOwnerKey() { return ownerKey; }
    public int getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public String getProductName() { return productName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public List<OrderLine> getItems() {
        return items != null && !items.isEmpty() ? List.copyOf(items)
                : (unitPrice == null ? List.of() :
                    List.of(new OrderLine(productId, quantity, productName, unitPrice)));
    }
    public BigDecimal getTotal() {
        return getItems().stream().map(OrderLine::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getReservationLeaseId() { return reservationLeaseId; }
    public Instant getReservationLeaseUntil() { return reservationLeaseUntil; }
    public String getPaymentStatus() { return paymentStatus; }
    public String getPaymentEventId() { return paymentEventId; }
    public Instant getPaymentStartedAt() { return paymentStartedAt; }
    public Instant getMockPaidAt() { return mockPaidAt; }
    // Existing orders without an expiry retain the documented 15-minute TTL.
    public Instant getExpiresAt() {
        return expiresAt != null ? expiresAt
                : createdAt == null ? null : createdAt.plus(Duration.ofMinutes(15));
    }
    public void setId(String id) { this.id = id; }
    public void setVersion(Long version) { this.version = version; }
    public void setOwnerKey(String ownerKey) { this.ownerKey = ownerKey; }
    public void setProductId(int productId) { this.productId = productId; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public void setProductName(String name) { this.productName = name; }
    public void setUnitPrice(BigDecimal price) { this.unitPrice = price; }
    public void setItems(List<OrderLine> items) {
        this.items = items == null ? null : new ArrayList<>(items);
    }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setStatus(String status) { this.status = status; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setReservationLeaseId(String id) { this.reservationLeaseId = id; }
    public void setReservationLeaseUntil(Instant until) { this.reservationLeaseUntil = until; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }
    public void setPaymentEventId(String paymentEventId) { this.paymentEventId = paymentEventId; }
    public void setPaymentStartedAt(Instant paymentStartedAt) { this.paymentStartedAt = paymentStartedAt; }
    public void setMockPaidAt(Instant mockPaidAt) { this.mockPaidAt = mockPaidAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
