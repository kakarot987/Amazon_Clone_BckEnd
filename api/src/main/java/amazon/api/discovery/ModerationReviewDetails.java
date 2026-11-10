package amazon.api.discovery;

/** Operator view. No reviewer owner key or reporter identity is exposed. */
public record ModerationReviewDetails(Long reviewId, int productId, String title,
                                      String content, boolean verifiedMockPurchase,
                                      String visibility, long reportCount) {}
