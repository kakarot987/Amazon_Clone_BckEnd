package amazon.api.discovery;

import java.time.Instant;

/** Anonymous public review display. Never exposes internal owner keys. */
public record CustomerReview(int productId, int rating, String title, String content,
                             Instant createdAt, Instant updatedAt, boolean mockPurchaseConfirmed) {}
