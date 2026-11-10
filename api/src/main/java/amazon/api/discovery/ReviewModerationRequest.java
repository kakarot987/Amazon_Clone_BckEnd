package amazon.api.discovery;

/** HIDE or RESTORE, with an idempotent operator event ID. */
public record ReviewModerationRequest(String eventId, String action, String reasonCode) {}
