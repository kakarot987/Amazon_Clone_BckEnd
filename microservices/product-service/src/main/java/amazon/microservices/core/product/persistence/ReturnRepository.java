package amazon.microservices.core.product.persistence;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface ReturnRepository extends ReactiveCrudRepository<ReturnEntity, String> {}
