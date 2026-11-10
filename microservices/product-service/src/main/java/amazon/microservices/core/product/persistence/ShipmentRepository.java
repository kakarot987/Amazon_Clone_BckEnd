package amazon.microservices.core.product.persistence;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface ShipmentRepository extends ReactiveCrudRepository<ShipmentEntity, String> {}
