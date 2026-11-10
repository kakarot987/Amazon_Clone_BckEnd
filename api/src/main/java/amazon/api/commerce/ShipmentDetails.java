package amazon.api.commerce;

import java.time.Instant;
import java.util.List;

/** Public view; does not expose a real shipping address or carrier booking. */
public record ShipmentDetails(String orderId, String shipmentId, String status,
                              String trackingNumber, String carrier,
                              String destinationZone, Instant createdAt, Instant updatedAt,
                              List<ShipmentEvent> history) {}
