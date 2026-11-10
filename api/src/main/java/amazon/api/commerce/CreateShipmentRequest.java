package amazon.api.commerce;

/** No real address or PII: a simulated destination-zone identifier only. */
public record CreateShipmentRequest(String orderId, String destinationZone) {}
