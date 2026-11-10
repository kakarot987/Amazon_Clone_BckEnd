package amazon.microservices.composite.product.services;

import amazon.api.core.product.Product;
import amazon.api.core.product.ProductPage;
import amazon.api.discovery.RelatedProducts;
import amazon.util.exceptions.InvalidInputException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * Public catalog endpoints, protected by the composite service's JWT scopes.
 * Writes publish asynchronous events and return 202 Accepted.
 */
@RestController
public class ProductCatalogCompositeController {
    private final ProductCompositeIntegration integration;

    public ProductCatalogCompositeController(ProductCompositeIntegration integration) {
        this.integration = integration;
    }

    @GetMapping(value = "/product-composite", produces = "application/json")
    public Mono<ProductPage> listProducts(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean inStock,
            @RequestParam(defaultValue = "productId") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new InvalidInputException("page must be >= 0 and size must be 1-100");
        if (query != null && query.length() > 100)
            throw new InvalidInputException("query must be at most 100 characters");
        if (category != null && category.length() > 120)
            throw new InvalidInputException("category must be at most 120 characters");
        if (!java.util.Set.of("productId", "productIdDesc", "name", "stockDesc").contains(sort))
            throw new InvalidInputException("Unsupported catalog sort");
        // Preserve the original integration contract for existing callers/tests.
        if (inStock == null && "productId".equals(sort))
            return integration.listProducts(query, category, page, size);
        return integration.listProducts(query, category, inStock, sort, page, size);
    }

    @GetMapping(value = "/product-composite/products/{productId}/related", produces = "application/json")
    public Mono<RelatedProducts> related(@PathVariable int productId,
                                         @RequestParam(defaultValue = "6") int limit) {
        if (productId < 1 || limit < 1 || limit > 20)
            throw new InvalidInputException("productId must be positive and limit must be 1-20");
        return integration.relatedProducts(productId, limit);
    }

    @ResponseStatus(HttpStatus.ACCEPTED)
    @PutMapping(value = "/product-composite/{productId}", consumes = "application/json")
    public Mono<Void> updateProduct(@PathVariable int productId, @RequestBody Product product) {
        if (product == null || productId < 1 || productId != product.getProductId())
            throw new InvalidInputException("path productId must match request body productId");
        if (product.getName() == null || product.getName().isBlank() || product.getName().length() > 200
                || product.getWeight() < 0)
            throw new InvalidInputException("invalid name or weight");
        return integration.updateProduct(product);
    }
}
