package amazon.microservices.core.recommendation.persistence;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
public interface ProcessedRecommendationEventRepository extends ReactiveCrudRepository<ProcessedRecommendationEvent, String> {}
