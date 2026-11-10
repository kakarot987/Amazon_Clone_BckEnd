package amazon.api.discovery;

import java.time.Instant;

/** Redacted moderation report; reporter identity is never returned. */
public record ReviewReportReceipt(Long reviewId, int productId, String reasonCode,
                                  Instant createdAt) {}
