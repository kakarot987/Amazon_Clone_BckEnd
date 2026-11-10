package amazon.microservices.core.product.persistence;

import amazon.api.commerce.ReturnEvent;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Single-order mock return. Changes are atomic within this one document. */
@Document(collection = "simulated_returns")
public class ReturnEntity {
    @Id private String id;
    @Version private Long version;
    private String ownerKey;
    private String reasonCode;
    private String state;
    private BigDecimal refundAmount;
    private String currency;
    private Instant createdAt;
    private Instant updatedAt;
    private List<ReturnEvent> history = new ArrayList<>();

    public ReturnEntity() {}

    public ReturnEntity(String orderId, String ownerKey, String reasonCode,
                        BigDecimal refundAmount, String currency) {
        this.id = orderId;
        this.ownerKey = ownerKey;
        this.reasonCode = reasonCode;
        this.state = "REQUESTED";
        this.refundAmount = refundAmount;
        this.currency = currency;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        this.history = new ArrayList<>(List.of(
                new ReturnEvent("return-requested", "REQUESTED", createdAt)));
    }

    public String getId() { return id; }
    public Long getVersion() { return version; }
    public String getOwnerKey() { return ownerKey; }
    public String getReasonCode() { return reasonCode; }
    public String getState() { return state; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<ReturnEvent> getHistory() { return history; }
    public void setId(String id) { this.id = id; }
    public void setVersion(Long version) { this.version = version; }
    public void setOwnerKey(String ownerKey) { this.ownerKey = ownerKey; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public void setState(String state) { this.state = state; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setHistory(List<ReturnEvent> history) { this.history = history; }
}
