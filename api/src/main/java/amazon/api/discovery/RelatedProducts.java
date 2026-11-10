package amazon.api.discovery;

import amazon.api.core.product.Product;
import java.util.List;

/** Deterministic related-category discovery, not personalized recommendations. */
public record RelatedProducts(int productId, String basis, List<Product> products) {}
