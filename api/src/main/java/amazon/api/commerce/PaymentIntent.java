package amazon.api.commerce;

import java.math.BigDecimal;
import java.time.Instant;

/** A simulated provider intent. No payment account or card is charged. */
public class PaymentIntent {
    private String paymentId;
    private String provider;
    private String status;
    private BigDecimal amount;
    private String currency;
    private Instant createdAt;
    private Instant expiresAt;

    public PaymentIntent() {}

    public PaymentIntent(String paymentId, String status, BigDecimal amount,
                         String currency, Instant createdAt, Instant expiresAt) {
        this.paymentId = paymentId;
        this.provider = "MOCK";
        this.status = status;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getPaymentId() { return paymentId; }
    public String getProvider() { return provider; }
    public String getStatus() { return status; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public void setProvider(String provider) { this.provider = provider; }
    public void setStatus(String status) { this.status = status; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
