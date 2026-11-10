package amazon.api.commerce;

/** Idempotent fulfillment event, supplied by the privileged mock operator. */
public record ShipmentStatusUpdate(String eventId, String status) {}
