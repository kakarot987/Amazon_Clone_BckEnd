package amazon.api.commerce;

import java.math.BigDecimal;

/** A cart line with a display-only snapshot of product metadata. */
public class CartItem {
    private int productId;
    private int quantity;
    private String name;
    private BigDecimal unitPrice;
    private String currency;

    public CartItem() {}

    public CartItem(int productId, int quantity, String name, BigDecimal unitPrice, String currency) {
        this.productId = productId;
        this.quantity = quantity;
        this.name = name;
        this.unitPrice = unitPrice;
        this.currency = currency;
    }

    public int getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public String getName() { return name; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public String getCurrency() { return currency; }
    public void setProductId(int productId) { this.productId = productId; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public void setName(String name) { this.name = name; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public void setCurrency(String currency) { this.currency = currency; }
}
