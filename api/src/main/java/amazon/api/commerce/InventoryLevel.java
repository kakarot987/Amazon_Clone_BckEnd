package amazon.api.commerce;

/** Available stock, not an order reservation. */
public class InventoryLevel {
    private int productId;
    private int available;

    public InventoryLevel() {}

    public InventoryLevel(int productId, int available) {
        this.productId = productId;
        this.available = available;
    }

    public int getProductId() { return productId; }
    public int getAvailable() { return available; }
    public void setProductId(int productId) { this.productId = productId; }
    public void setAvailable(int available) { this.available = available; }
}
