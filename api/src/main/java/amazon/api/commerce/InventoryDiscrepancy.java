package amazon.api.commerce;

/** Read-only reconciliation finding; never an automatic stock adjustment. */
public record InventoryDiscrepancy(int productId, String orderId, String kind, String detail) {}
