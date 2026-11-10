package amazon.microservices.core.review.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CustomerReviewRepository extends JpaRepository<CustomerReviewEntity, Long> {
    Optional<CustomerReviewEntity> findByProductIdAndOwnerKey(int productId, String ownerKey);

    Page<CustomerReviewEntity> findByProductId(int productId, Pageable pageable);

    /** Database aggregation avoids fetching unbounded reviews into memory. */
    @Query("select r.rating as rating, count(r) as reviewCount " +
           "from CustomerReviewEntity r where r.productId = :productId group by r.rating")
    List<RatingBucket> ratingCounts(@Param("productId") int productId);

    interface RatingBucket {
        Integer getRating();
        Long getReviewCount();
    }
}
