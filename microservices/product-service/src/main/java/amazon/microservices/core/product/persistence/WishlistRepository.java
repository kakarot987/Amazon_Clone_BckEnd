package amazon.microservices.core.product.persistence;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface WishlistRepository extends ReactiveCrudRepository<WishlistEntity, String> {}
