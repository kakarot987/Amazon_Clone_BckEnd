package amazon.microservices.core.review.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CustomerReviewReportRepository extends JpaRepository<CustomerReviewReportEntity, Long> {
    Optional<CustomerReviewReportEntity> findByReviewIdAndReporterKey(Long reviewId, String reporterKey);
    Page<CustomerReviewReportEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
    long countByReviewId(Long reviewId);
    void deleteByReviewId(Long reviewId);
}
