package amazon.microservices.core.product.services;

import amazon.api.core.product.Product;
import reactor.core.publisher.Mono;

/** Internal operation used by both HTTP and event consumers. */
public interface ProductCatalogOperations {
    Mono<Product> updateProduct(int productId, Product product);
}
