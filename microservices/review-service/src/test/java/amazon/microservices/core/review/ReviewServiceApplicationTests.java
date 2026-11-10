package amazon.microservices.core.review;

import amazon.api.core.review.Review;
import amazon.api.event.Event;
import amazon.microservices.core.review.persistence.ReviewRepository;
import amazon.microservices.core.review.persistence.ProcessedReviewEventRepository;
import amazon.microservices.core.review.persistence.CustomerReviewRepository;
import amazon.api.discovery.VerifiedCustomerReviewSubmission;
import amazon.api.discovery.ReviewReportRequest;
import amazon.api.discovery.ReviewModerationRequest;
import amazon.microservices.core.review.persistence.CustomerReviewReportRepository;
import amazon.microservices.core.review.persistence.ReviewModerationEventRepository;
import amazon.util.exceptions.InvalidInputException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;


import java.util.function.Consumer;

import static amazon.api.event.Event.Type.CREATE;
import static amazon.api.event.Event.Type.DELETE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {
  "spring.cloud.stream.defaultBinder=rabbit",
  "logging.level.amazon=DEBUG",
  "eureka.client.enabled=false",
  "spring.jpa.hibernate.ddl-auto=update",
  "spring.cloud.config.enabled=false","spring.cloud.compatibility-verifier.enabled=false"})
class ReviewServiceApplicationTests extends MySqlTestBase {

  @Autowired
  private WebTestClient client;

  @Autowired
  private ReviewRepository repository;

  @Autowired
  private ProcessedReviewEventRepository processedEvents;

  @Autowired
  private CustomerReviewRepository customerReviews;

  @Autowired
  private CustomerReviewReportRepository reviewReports;

  @Autowired
  private ReviewModerationEventRepository moderationEvents;

  @Autowired
  @Qualifier("messageProcessor")
  private Consumer<Event<Integer, Review>> messageProcessor;

  @BeforeEach
  void setupDb() {
    repository.deleteAll();
    processedEvents.deleteAll();
    moderationEvents.deleteAll();
    reviewReports.deleteAll();
    customerReviews.deleteAll();
  }

  @Test
  void getReviewsByProductId() {

    int productId = 1;

    assertEquals(0, repository.findByProductId(productId).size());

    sendCreateReviewEvent(productId, 1);
    sendCreateReviewEvent(productId, 2);
    sendCreateReviewEvent(productId, 3);

    assertEquals(3, repository.findByProductId(productId).size());

    getAndVerifyReviewsByProductId(productId, OK)
      .jsonPath("$.length()").isEqualTo(3)
      .jsonPath("$[2].productId").isEqualTo(productId)
      .jsonPath("$[2].reviewId").isEqualTo(3);
  }

  @Test
  void duplicateError() {

    int productId = 1;
    int reviewId = 1;

    assertEquals(0, repository.count());

    sendCreateReviewEvent(productId, reviewId);

    assertEquals(1, repository.count());

    InvalidInputException thrown = assertThrows(
      InvalidInputException.class,
      () -> sendCreateReviewEvent(productId, reviewId),
      "Expected a InvalidInputException here!");
    assertEquals("Duplicate key, Product Id: 1, Review Id:1", thrown.getMessage());

    assertEquals(1, repository.count());
  }

  @Test
  void deleteReviews() {

    int productId = 1;
    int reviewId = 1;

    sendCreateReviewEvent(productId, reviewId);
    assertEquals(1, repository.findByProductId(productId).size());

    sendDeleteReviewEvent(productId);
    assertEquals(0, repository.findByProductId(productId).size());

    sendDeleteReviewEvent(productId);
  }

  @Test
  void getReviewsMissingParameter() {

    getAndVerifyReviewsByProductId("", BAD_REQUEST)
      .jsonPath("$.path").isEqualTo("/review")
      .jsonPath("$.message").isEqualTo("Required query parameter 'productId' is not present.");
  }

  @Test
  void getReviewsInvalidParameter() {

    getAndVerifyReviewsByProductId("?productId=no-integer", BAD_REQUEST)
      .jsonPath("$.path").isEqualTo("/review")
      .jsonPath("$.message").isEqualTo("Type mismatch.");
  }

  @Test
  void getReviewsNotFound() {

    getAndVerifyReviewsByProductId("?productId=213", OK)
      .jsonPath("$.length()").isEqualTo(0);
  }

  @Test
  void getReviewsInvalidParameterNegativeValue() {

    int productIdInvalid = -1;

    getAndVerifyReviewsByProductId("?productId=" + productIdInvalid, UNPROCESSABLE_ENTITY)
      .jsonPath("$.path").isEqualTo("/review")
      .jsonPath("$.message").isEqualTo("Invalid productId: " + productIdInvalid);
  }

  private WebTestClient.BodyContentSpec getAndVerifyReviewsByProductId(int productId, HttpStatus expectedStatus) {
    return getAndVerifyReviewsByProductId("?productId=" + productId, expectedStatus);
  }

  private WebTestClient.BodyContentSpec getAndVerifyReviewsByProductId(String productIdQuery, HttpStatus expectedStatus) {
    return client.get()
      .uri("/review" + productIdQuery)
      .accept(APPLICATION_JSON)
      .exchange()
      .expectStatus().isEqualTo(expectedStatus)
      .expectHeader().contentType(APPLICATION_JSON)
      .expectBody();
  }


  private static final String REVIEW_OWNER_A =
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  private static final String REVIEW_OWNER_B =
    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

  @Test
  void customerReviewsAreOwnedEditableAndAverageRatingsUseSqlAggregation() {
    String ownerA = "/internal/customer-reviews/31/owner/" + REVIEW_OWNER_A;
    String ownerB = "/internal/customer-reviews/31/owner/" + REVIEW_OWNER_B;
    client.put().uri(ownerA).contentType(APPLICATION_JSON)
      .bodyValue(new VerifiedCustomerReviewSubmission(5, "Excellent", "Loved this item", true))
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.rating").isEqualTo(5)
      .jsonPath("$.title").isEqualTo("Excellent");
    client.put().uri(ownerA).contentType(APPLICATION_JSON)
      .bodyValue(new VerifiedCustomerReviewSubmission(3, "Good", "Still useful", true))
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.rating").isEqualTo(3);
    client.put().uri(ownerB).contentType(APPLICATION_JSON)
      .bodyValue(new VerifiedCustomerReviewSubmission(1, "Poor", "Not for me", false))
      .exchange().expectStatus().isOk();
    assertEquals(2L, customerReviews.count());

    client.get().uri("/internal/customer-reviews/31/ratings")
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.reviewCount").isEqualTo(2)
      .jsonPath("$.averageRating").isEqualTo(2.0)
      .jsonPath("$.starCounts['1']").isEqualTo(1)
      .jsonPath("$.starCounts['3']").isEqualTo(1);

    client.get().uri("/internal/customer-reviews/31?page=0&size=1&sort=highest")
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.totalElements").isEqualTo(2)
      .jsonPath("$.totalPages").isEqualTo(2)
      .jsonPath("$.reviews[0].rating").isEqualTo(3)
      .jsonPath("$.reviews.length()").isEqualTo(1);

    client.delete().uri(ownerB).exchange().expectStatus().isOk();
    client.delete().uri(ownerB).exchange().expectStatus().isOk();
    client.get().uri("/internal/customer-reviews/31/ratings")
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.reviewCount").isEqualTo(1)
      .jsonPath("$.averageRating").isEqualTo(3.0);
  }

  @Test
  void reviewsRejectInvalidInputsAndBoundPageSize() {
    String url = "/internal/customer-reviews/32/owner/" + REVIEW_OWNER_A;
    for (int invalidRating : new int[] {0, 6}) {
      client.put().uri(url).contentType(APPLICATION_JSON)
        .bodyValue(new VerifiedCustomerReviewSubmission(invalidRating, "Title", "Body", false))
        .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    }
    client.put().uri(url).contentType(APPLICATION_JSON)
      .bodyValue(new VerifiedCustomerReviewSubmission(4, " ", "Body", false))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.get().uri("/internal/customer-reviews/32?page=-1")
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.get().uri("/internal/customer-reviews/32?size=101")
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.get().uri("/internal/customer-reviews/32?sort=invalid")
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.get().uri("/internal/customer-reviews/32/ratings")
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.reviewCount").isEqualTo(0);
    assertEquals(0L, customerReviews.count());
  }


  @Test
  void mockPurchaseBadgeAndHiddenRatingsAreHandledSeparately() {
    String ownerA = "/internal/customer-reviews/41/owner/" + REVIEW_OWNER_A;
    String ownerB = "/internal/customer-reviews/41/owner/" + REVIEW_OWNER_B;
    client.put().uri(ownerA).contentType(APPLICATION_JSON)
      .bodyValue(new VerifiedCustomerReviewSubmission(5, "Great", "Excellent mock product", true))
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.mockPurchaseConfirmed").isEqualTo(true);
    client.put().uri(ownerB).contentType(APPLICATION_JSON)
      .bodyValue(new VerifiedCustomerReviewSubmission(1, "Bad", "Not useful", false))
      .exchange().expectStatus().isOk();
    Long id = customerReviews.findByProductIdAndOwnerKey(41, REVIEW_OWNER_B).orElseThrow().getId();

    client.post().uri("/internal/review-moderation/products/41/reviews/" + id + "/reports/owner/" + REVIEW_OWNER_A)
      .contentType(APPLICATION_JSON).bodyValue(new ReviewReportRequest("SPAM"))
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.reasonCode").isEqualTo("SPAM");
    client.post().uri("/internal/review-moderation/products/41/reviews/" + id + "/reports/owner/" + REVIEW_OWNER_A)
      .contentType(APPLICATION_JSON).bodyValue(new ReviewReportRequest("SPAM"))
      .exchange().expectStatus().isOk();
    assertEquals(1L, reviewReports.count());

    client.post().uri("/internal/review-moderation/products/41/reviews/" + id + "/decision")
      .contentType(APPLICATION_JSON)
      .bodyValue(new ReviewModerationRequest("mod-event-hide-41", "HIDE", "SPAM"))
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.visibility").isEqualTo("HIDDEN")
      .jsonPath("$.reportCount").isEqualTo(1);
    client.post().uri("/internal/review-moderation/products/41/reviews/" + id + "/decision")
      .contentType(APPLICATION_JSON)
      .bodyValue(new ReviewModerationRequest("mod-event-hide-41", "HIDE", "SPAM"))
      .exchange().expectStatus().isOk();
    assertEquals(1L, moderationEvents.count());

    client.get().uri("/internal/customer-reviews/41")
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.reviews[0].rating").isEqualTo(5);
    client.get().uri("/internal/customer-reviews/41/ratings")
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.reviewCount").isEqualTo(1)
      .jsonPath("$.averageRating").isEqualTo(5.0);

    client.post().uri("/internal/review-moderation/products/41/reviews/" + id + "/decision")
      .contentType(APPLICATION_JSON)
      .bodyValue(new ReviewModerationRequest("mod-event-restore-41", "RESTORE", "SPAM"))
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.visibility").isEqualTo("VISIBLE");
    client.get().uri("/internal/customer-reviews/41/ratings")
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.reviewCount").isEqualTo(2);
  }

  @Test
  void reviewReportsRejectSelfReportsAndConflictingModeratorReplays() {
    String owner = "/internal/customer-reviews/42/owner/" + REVIEW_OWNER_A;
    client.put().uri(owner).contentType(APPLICATION_JSON)
      .bodyValue(new VerifiedCustomerReviewSubmission(4, "Nice", "Worth testing", false))
      .exchange().expectStatus().isOk();
    Long reviewId = customerReviews.findByProductIdAndOwnerKey(42, REVIEW_OWNER_A).orElseThrow().getId();

    String report = "/internal/review-moderation/products/42/reviews/" + reviewId + "/reports/owner/";
    client.post().uri(report + REVIEW_OWNER_A)
      .contentType(APPLICATION_JSON).bodyValue(new ReviewReportRequest("ABUSE"))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.post().uri(report + REVIEW_OWNER_B)
      .contentType(APPLICATION_JSON).bodyValue(new ReviewReportRequest("OFF_TOPIC"))
      .exchange().expectStatus().isOk();
    client.post().uri(report + REVIEW_OWNER_B)
      .contentType(APPLICATION_JSON).bodyValue(new ReviewReportRequest("SPAM"))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);

    String decision = "/internal/review-moderation/products/42/reviews/" + reviewId + "/decision";
    client.post().uri(decision).contentType(APPLICATION_JSON)
      .bodyValue(new ReviewModerationRequest("mod-event-unique-42", "HIDE", "ABUSE"))
      .exchange().expectStatus().isOk();
    client.post().uri(decision).contentType(APPLICATION_JSON)
      .bodyValue(new ReviewModerationRequest("mod-event-unique-42", "RESTORE", "ABUSE"))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.get().uri("/internal/review-moderation/reports?page=0&size=10")
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.totalElements").isEqualTo(1);
  }


  @Test
  void reviewEventReplayIsDeduplicatedAndMismatchedPayloadRejected() {
    Review review = new Review(993, 1, "Sample", "Subject", "Body", "SA");
    messageProcessor.accept(new Event<>(CREATE, 993, review, "review-replay-993"));
    messageProcessor.accept(new Event<>(CREATE, 993, review, "review-replay-993"));
    assertEquals(1L, repository.count());
    assertEquals(1L, processedEvents.count());

    Review altered = new Review(993, 1, "Sample", "Subject", "Altered", "SA");
    assertThrows(amazon.util.exceptions.EventProcessingException.class, () -> messageProcessor.accept(
        new Event<>(CREATE, 993, altered, "review-replay-993")));
    assertEquals(1L, repository.count());
  }

  @Test
  void failedReviewMutationRollsBackInboxReceipt() {
    Review review = new Review(994, 1, "Sample", "Subject", "Body", "SA");
    messageProcessor.accept(new Event<>(CREATE, 994, review, "review-first-994"));
    long before = processedEvents.count();

    assertThrows(InvalidInputException.class, () -> messageProcessor.accept(
        new Event<>(CREATE, 994, review, "review-another-994")));
    assertEquals(before, processedEvents.count());
    assertEquals(1L, repository.count());
  }

  private void sendCreateReviewEvent(int productId, int reviewId) {
    Review review = new Review(productId, reviewId, "Author " + reviewId, "Subject " + reviewId, "Content " + reviewId, "SA");
    Event<Integer, Review> event = new Event(CREATE, productId, review);
    messageProcessor.accept(event);
  }

  private void sendDeleteReviewEvent(int productId) {
    Event<Integer, Review> event = new Event(DELETE, productId, null);
    messageProcessor.accept(event);
  }
}