package amazon.microservices.core.product.persistence;

import java.time.Instant;

/** A saved product reference, not a product snapshot or stock reservation. */
public record WishlistEntry(int productId, Instant savedAt) {}
