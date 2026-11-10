package amazon.api.commerce;

/** Owner-scoped order with optional shipping and return summaries. */
public record OrderHistoryItem(OrderReceipt order, OrderShipmentSummary shipment,
                               OrderReturnSummary returnSummary) {}
