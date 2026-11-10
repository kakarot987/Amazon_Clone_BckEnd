package amazon.api.discovery;

/** Internal-only input; verifiedMockPurchase must be derived from order service. */
public record VerifiedCustomerReviewSubmission(int rating, String title, String content,
                                               boolean verifiedMockPurchase) {}
