package amazon.api.commerce;

/** PUT replaces the quantity of one cart item. */
public class QuantityRequest {
    private int quantity;

    public QuantityRequest() {}

    public QuantityRequest(int quantity) { this.quantity = quantity; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
