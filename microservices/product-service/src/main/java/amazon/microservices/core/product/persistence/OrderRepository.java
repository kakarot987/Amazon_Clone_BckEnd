package amazon.microservices.core.product.persistence;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface OrderRepository extends ReactiveCrudRepository<OrderEntity, String> {
    Mono<Boolean> existsByOwnerKeyAndProductIdAndStatus(String ownerKey, int productId, String status);
    Mono<Boolean> existsByOwnerKeyAndItemsProductIdAndStatus(String ownerKey, int productId, String status);
}
