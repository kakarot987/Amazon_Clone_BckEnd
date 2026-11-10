package amazon.api.discovery;

import java.math.BigDecimal;
import java.util.Map;

/** Rating distribution includes only authenticated customer-submitted reviews. */
public record RatingSummary(int productId, long reviewCount, BigDecimal averageRating,
                            Map<Integer, Long> starCounts) {}
