package amazon.api.commerce;

import java.time.Instant;

/** Observed persisted event only, not an inferred status-change timestamp. */
public record OrderTimelineEvent(String source, String status, Instant occurredAt) {}
