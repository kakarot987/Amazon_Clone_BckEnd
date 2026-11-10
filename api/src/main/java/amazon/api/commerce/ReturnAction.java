package amazon.api.commerce;

/** Idempotent operator action: event ID persists with the return. */
public record ReturnAction(String eventId) {}
