package amazon.api.commerce;

import java.math.BigDecimal;
import java.time.Instant;

/** Reservation only: RESERVED does not mean paid or fulfilled. */
public class OrderReceipt {
    private String orderId;
    private String status;
    private int productId;
    private int quantity;
    private String productName;
    private BigDecimal unitPrice;
    private BigDecimal total;
    private String currency;
    private Instant createdAt;
    public OrderReceipt() {}
    public OrderReceipt(String id, String status, int productId, int quantity,
                        String name, BigDecimal price, String currency, Instant createdAt) {
        this.orderId = id;
        this.status = status;
        this.productId = productId;
        this.quantity = quantity;
        this.productName = name;
        this.unitPrice = price;
        this.total = price.multiply(BigDecimal.valueOf(quantity));
        this.currency = currency;
        this.createdAt = createdAt;
    }
    public String getOrderId() { return orderId; }
    public String getStatus() { return status; }
    public int getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public String getProductName() { return productName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getTotal() { return total; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public void setOrderId(String id) { this.orderId = id; }
    public void setStatus(String status) { this.status = status; }
    public void setProductId(int value) { this.productId = value; }
    public void setQuantity(int value) { this.quantity = value; }
    public void setProductName(String name) { this.productName = name; }
    public void setUnitPrice(BigDecimal price) { this.unitPrice = price; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
