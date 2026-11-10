package amazon.api.commerce;

import java.math.BigDecimal;

/** Immutable checkout-time product snapshot; no price or stock promises. */
public record OrderLine(int productId, int quantity, String productName,
                        BigDecimal unitPrice, BigDecimal lineTotal) {
    public OrderLine(int productId, int quantity, String name, BigDecimal price) {
        this(productId, quantity, name, price,
                price.multiply(BigDecimal.valueOf(quantity)));
    }
}
