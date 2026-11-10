package amazon.api.discovery;

import java.util.List;

public record CustomerReviewPage(List<CustomerReview> reviews, int page, int size,
                                 long totalElements, long totalPages) {}
