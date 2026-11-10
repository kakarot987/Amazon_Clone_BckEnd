package amazon.api.commerce;

import java.time.Instant;

/** Mock carrier status only. A tracking number is not a real carrier booking. */
public record OrderShipmentSummary(String status, String trackingNumber,
                                   Instant updatedAt) {}
