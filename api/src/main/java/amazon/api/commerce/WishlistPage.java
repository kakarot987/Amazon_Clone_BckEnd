package amazon.api.commerce;

import java.time.Instant;
import java.util.List;

/** Pagination reflects the filtered set of live wishlist entries. */
public record WishlistPage(List<WishlistItem> items, int page, int size,
                           int totalElements, int totalPages, Instant updatedAt) {}
