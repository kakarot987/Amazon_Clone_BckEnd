package amazon.api.event;

import java.time.Instant;

/** Transactionally recorded consumer inbox evidence, not a global saga outcome. */
public record ReceiptEvidence(String eventId, String fingerprint, Instant processedAt) {}
