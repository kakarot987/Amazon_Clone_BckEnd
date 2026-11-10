package amazon.microservices.core.review.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
public interface ReviewModerationEventRepository extends JpaRepository<ReviewModerationEventEntity, String> {}
