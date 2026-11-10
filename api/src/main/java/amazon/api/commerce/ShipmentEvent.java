package amazon.api.commerce;

import java.time.Instant;

/** Durable audit trail of simulated shipping milestones. */
public record ShipmentEvent(String eventId, String status, Instant occurredAt) {}
