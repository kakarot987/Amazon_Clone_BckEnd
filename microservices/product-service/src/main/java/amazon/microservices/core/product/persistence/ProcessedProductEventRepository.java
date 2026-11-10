package amazon.microservices.core.product.persistence;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
public interface ProcessedProductEventRepository extends ReactiveCrudRepository<ProcessedProductEvent, String> {}
