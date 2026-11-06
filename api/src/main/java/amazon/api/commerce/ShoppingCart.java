package amazon.api.commerce;

import java.time.Instant;
import java.util.List;

/** Owner is obtained from the authenticated token, never from the request. */
public class ShoppingCart {
    private List<CartItem> items;
    private Instant updatedAt;

    public ShoppingCart() {}

    public ShoppingCart(List<CartItem> items, Instant updatedAt) {
        this.items = items;
        this.updatedAt = updatedAt;
    }

    public List<CartItem> getItems() { return items; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setItems(List<CartItem> items) { this.items = items; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
