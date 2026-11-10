package amazon.api.discovery;

/** Customer-authored review; identity is derived exclusively from verified JWT. */
public record CustomerReviewRequest(int rating, String title, String content) {}
