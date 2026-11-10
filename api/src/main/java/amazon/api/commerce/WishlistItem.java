package amazon.api.commerce;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Live catalog metadata for a saved item. Saving never reserves inventory.
 * If a product is deleted, its saved entry remains with productExists=false.
 */
public record WishlistItem(int productId, Instant savedAt, boolean productExists,
                           boolean inStock, String name, String category,
                           BigDecimal price, String currency, Integer stockQuantity,
                           String imageUrl) {}
