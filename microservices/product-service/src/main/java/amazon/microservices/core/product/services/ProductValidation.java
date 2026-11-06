package amazon.microservices.core.product.services;

import amazon.api.core.product.Product;
import amazon.util.exceptions.InvalidInputException;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Currency;
import java.util.List;

/** Validates newly submitted catalog data while allowing legacy null fields. */
public final class ProductValidation {
    private ProductValidation() {}

    public static void validate(Product product) {
        if (product == null || product.getProductId() < 1)
            throw new InvalidInputException("productId must be positive");
        if (product.getName() == null || product.getName().isBlank() || product.getName().length() > 200)
            throw new InvalidInputException("name is required (max 200 characters)");
        if (product.getWeight() < 0)
            throw new InvalidInputException("weight must be non-negative");
        if (product.getSku() != null && (product.getSku().isBlank() || product.getSku().length() > 80))
            throw new InvalidInputException("sku must be 1-80 characters");
        if (product.getDescription() != null && product.getDescription().length() > 5000)
            throw new InvalidInputException("description must be at most 5000 characters");
        if (product.getCategory() != null && (product.getCategory().isBlank() || product.getCategory().length() > 120))
            throw new InvalidInputException("category must be 1-120 characters");
        if (product.getPrice() != null && (product.getPrice().signum() < 0 || product.getPrice().scale() > 2))
            throw new InvalidInputException("price must be non-negative with at most 2 decimal places");
        if (product.getStockQuantity() != null && product.getStockQuantity() < 0)
            throw new InvalidInputException("stockQuantity must be non-negative");
        if (product.getCurrency() != null) {
            try {
                if (!product.getCurrency().matches("[A-Z]{3}")) throw new IllegalArgumentException();
                Currency.getInstance(product.getCurrency());
            } catch (IllegalArgumentException ex) {
                throw new InvalidInputException("currency must be a valid ISO 4217 code");
            }
        }
        if ((product.getPrice() == null) != (product.getCurrency() == null))
            throw new InvalidInputException("price and currency must both be supplied");
        List<String> imageUrls = product.getImageUrls();
        if (imageUrls != null) {
            if (imageUrls.size() > 10) throw new InvalidInputException("at most 10 image URLs allowed");
            for (String url : imageUrls) {
                try {
                    URI parsed = URI.create(url);
                    if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null)
                        throw new IllegalArgumentException();
                } catch (RuntimeException ex) {
                    throw new InvalidInputException("image URLs must be absolute HTTPS URLs");
                }
            }
        }
    }
}
