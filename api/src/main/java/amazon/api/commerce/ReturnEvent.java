package amazon.api.commerce;

import java.time.Instant;

public record ReturnEvent(String eventId, String state, Instant occurredAt) {}
