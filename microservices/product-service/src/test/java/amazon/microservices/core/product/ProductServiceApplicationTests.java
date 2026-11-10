package amazon.microservices.core.product;

import amazon.api.core.product.Product;
import amazon.api.event.Event;
import amazon.microservices.core.product.persistence.ProductRepository;
import amazon.microservices.core.product.persistence.CartRepository;
import amazon.microservices.core.product.persistence.OrderRepository;
import amazon.microservices.core.product.persistence.ShipmentRepository;
import amazon.microservices.core.product.persistence.ReturnRepository;
import amazon.microservices.core.product.persistence.WishlistRepository;
import amazon.microservices.core.product.persistence.ShipmentEntity;
import amazon.microservices.core.product.persistence.ReturnEntity;
import amazon.api.commerce.ShipmentEvent;
import amazon.api.commerce.ReturnRequest;
import amazon.api.commerce.ReturnAction;
import amazon.api.commerce.CreateShipmentRequest;
import amazon.api.commerce.ShipmentStatusUpdate;
import amazon.microservices.core.product.persistence.OrderEntity;
import amazon.microservices.core.product.services.OrderRecoveryWorker;
import java.time.Instant;
import amazon.api.commerce.StockAdjustment;
import amazon.api.commerce.QuantityRequest;
import amazon.util.exceptions.InvalidInputException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

import static amazon.api.event.Event.Type.CREATE;
import static amazon.api.event.Event.Type.DELETE;
import static amazon.api.event.Event.Type.UPDATE;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {
  "app.orders.recovery-interval=PT1H",
  "MOCK_PAYMENT_WEBHOOK_SECRET=mock-test-signing-secret-with-more-than-32-characters",
  "eureka.client.enabled=false",
  "spring.cloud.config.enabled=false","spring.main.allow-bean-definition-overriding=true","spring.cloud.compatibility-verifier.enabled=false"})
class ProductServiceApplicationTests extends MongoDbTestBase {

  @Autowired
  private WebTestClient client;

  @Autowired
  private ProductRepository repository;

  @Autowired
  private CartRepository cartRepository;

  @Autowired
  private OrderRepository orderRepository;

  @Autowired
  private ShipmentRepository shipmentRepository;

  @Autowired
  private ReturnRepository returnRepository;

  @Autowired
  private WishlistRepository wishlistRepository;

  @Autowired
  private OrderRecoveryWorker recoveryWorker;

  @Autowired
  @Qualifier("messageProcessor")
  private Consumer<Event<Integer, Product>> messageProcessor;

  @BeforeEach
  void setupDb() {
    repository.deleteAll().block();
    cartRepository.deleteAll().block();
    orderRepository.deleteAll().block();
    shipmentRepository.deleteAll().block();
    returnRepository.deleteAll().block();
    wishlistRepository.deleteAll().block();
  }

  @Test
  void getProductById() {

    int productId = 1;

    assertNull(repository.findByProductId(productId).block());
    assertEquals(0, (long)repository.count().block());

    sendCreateProductEvent(productId);

    assertNotNull(repository.findByProductId(productId).block());
    assertEquals(1, (long)repository.count().block());

    getAndVerifyProduct(productId, OK)
      .jsonPath("$.productId").isEqualTo(productId);
  }

  @Test
  void duplicateError() {

    int productId = 1;

    assertNull(repository.findByProductId(productId).block());

    sendCreateProductEvent(productId);

    assertNotNull(repository.findByProductId(productId).block());

    InvalidInputException thrown = assertThrows(
      InvalidInputException.class,
      () -> sendCreateProductEvent(productId),
      "Expected a InvalidInputException here!");
    assertEquals("Duplicate key, Product Id: " + productId, thrown.getMessage());
  }

  @Test
  void deleteProduct() {

    int productId = 1;

    sendCreateProductEvent(productId);
    assertNotNull(repository.findByProductId(productId).block());

    sendDeleteProductEvent(productId);
    assertNull(repository.findByProductId(productId).block());

    sendDeleteProductEvent(productId);
  }

  @Test
  void getProductInvalidParameterString() {

    getAndVerifyProduct("/no-integer", BAD_REQUEST)
      .jsonPath("$.path").isEqualTo("/product/no-integer")
      .jsonPath("$.message").isEqualTo("Type mismatch.");
  }

  @Test
  void getProductNotFound() {

    int productIdNotFound = 13;
    getAndVerifyProduct(productIdNotFound, NOT_FOUND)
      .jsonPath("$.path").isEqualTo("/product/" + productIdNotFound)
      .jsonPath("$.message").isEqualTo("No product found for productId: " + productIdNotFound);
  }

  @Test
  void getProductInvalidParameterNegativeValue() {

    int productIdInvalid = -1;

    getAndVerifyProduct(productIdInvalid, UNPROCESSABLE_ENTITY)
      .jsonPath("$.path").isEqualTo("/product/" + productIdInvalid)
      .jsonPath("$.message").isEqualTo("Invalid productId: " + productIdInvalid);
  }

  private WebTestClient.BodyContentSpec getAndVerifyProduct(int productId, HttpStatus expectedStatus) {
    return getAndVerifyProduct("/" + productId, expectedStatus);
  }

  private WebTestClient.BodyContentSpec getAndVerifyProduct(String productIdPath, HttpStatus expectedStatus) {
    return client.get()
      .uri("/product" + productIdPath)
      .accept(APPLICATION_JSON)
      .exchange()
      .expectStatus().isEqualTo(expectedStatus)
      .expectHeader().contentType(APPLICATION_JSON)
      .expectBody();
  }

  @Test
  void catalogSearchAndPagination() {
    Product p1 = new Product(10, "Running Shoes", 2, "SA");
    p1.setCategory("footwear");
    p1.setSku("SHOE-10");
    p1.setPrice(new java.math.BigDecimal("49.99"));
    p1.setCurrency("USD");
    Product p2 = new Product(11, "Walking Shoes", 2, "SA");
    p2.setCategory("footwear");
    Product p3 = new Product(12, "Coffee Mug", 1, "SA");
    p3.setCategory("kitchen");
    messageProcessor.accept(new Event<>(CREATE, 10, p1));
    messageProcessor.accept(new Event<>(CREATE, 11, p2));
    messageProcessor.accept(new Event<>(CREATE, 12, p3));

    client.get().uri("/products?query=shoes&category=footwear&page=0&size=1")
      .exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.totalElements").isEqualTo(2)
      .jsonPath("$.totalPages").isEqualTo(2)
      .jsonPath("$.products.length()").isEqualTo(1)
      .jsonPath("$.products[0].sku").isEqualTo("SHOE-10")
      .jsonPath("$.products[0].price").isEqualTo(49.99);

    client.get().uri("/products?query=shoes&category=footwear&page=1&size=1")
      .exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.products[0].productId").isEqualTo(11);

    client.get().uri("/products?page=-1&size=20")
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.get().uri("/products?page=0&size=101")
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
  }

  @Test
  void catalogUpdatesAndValidation() {
    sendCreateProductEvent(20);
    Product update = new Product(20, "Updated Camera", 3, null);
    update.setSku("CAM-20");
    update.setDescription("Mirrorless camera");
    update.setCategory("electronics");
    update.setStockQuantity(5);
    update.setPrice(new java.math.BigDecimal("599.00"));
    update.setCurrency("USD");
    update.setImageUrls(java.util.List.of("https://example.com/camera.jpg"));

    messageProcessor.accept(new Event<>(UPDATE, 20, update));
    getAndVerifyProduct(20, OK)
      .jsonPath("$.name").isEqualTo("Updated Camera")
      .jsonPath("$.stockQuantity").isEqualTo(5)
      .jsonPath("$.category").isEqualTo("electronics")
      .jsonPath("$.imageUrls[0]").isEqualTo("https://example.com/camera.jpg");

    Product invalid = new Product(20, "Invalid", 3, null);
    invalid.setPrice(new java.math.BigDecimal("-1.00"));
    assertThrows(InvalidInputException.class,
      () -> messageProcessor.accept(new Event<>(UPDATE, 20, invalid)));

    client.put().uri("/product/21").bodyValue(update)
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
  }


  private static final String CART_OWNER =
      "000000000000000000000000000000000000000000000000000000000000000a";
  private static final String OTHER_OWNER =
      "000000000000000000000000000000000000000000000000000000000000000b";

  @Test
  void cartIsOwnerScopedAndDoesNotReserveInventory() {
    Product product = new Product(77, "Camera bag", 1, null);
    product.setStockQuantity(5);
    product.setPrice(new java.math.BigDecimal("19.99"));
    product.setCurrency("USD");
    messageProcessor.accept(new Event<>(CREATE, 77, product));

    client.put().uri("/internal/carts/" + CART_OWNER + "/items/77")
      .contentType(APPLICATION_JSON).bodyValue(new QuantityRequest(3))
      .exchange().expectStatus().isOk()
      .expectBody()
      .jsonPath("$.items[0].quantity").isEqualTo(3)
      .jsonPath("$.items[0].name").isEqualTo("Camera bag")
      .jsonPath("$.items[0].unitPrice").isEqualTo(19.99);

    client.get().uri("/internal/carts/" + OTHER_OWNER)
      .exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.items.length()").isEqualTo(0);

    client.get().uri("/internal/inventory/77")
      .exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.available").isEqualTo(5);

    client.delete().uri("/internal/carts/" + CART_OWNER + "/items/77")
      .exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.items.length()").isEqualTo(0);
  }

  @Test
  void cartRejectsMissingProductAndInvalidQuantities() {
    client.put().uri("/internal/carts/" + CART_OWNER + "/items/999")
      .contentType(APPLICATION_JSON).bodyValue(new QuantityRequest(1))
      .exchange().expectStatus().isEqualTo(NOT_FOUND);

    Product product = new Product(78, "T-shirt", 1, null);
    product.setStockQuantity(2);
    messageProcessor.accept(new Event<>(CREATE, 78, product));

    for (int invalidQuantity : new int[] {0, 3, 100}) {
      client.put().uri("/internal/carts/" + CART_OWNER + "/items/78")
        .contentType(APPLICATION_JSON).bodyValue(new QuantityRequest(invalidQuantity))
        .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    }
    client.get().uri("/internal/carts/" + CART_OWNER)
      .exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.items.length()").isEqualTo(0);
  }

  @Test
  void stockAdjustmentsNeverProduceNegativeInventory() {
    Product product = new Product(79, "Monitor", 5, null);
    product.setStockQuantity(3);
    messageProcessor.accept(new Event<>(CREATE, 79, product));

    client.post().uri("/internal/inventory/79/adjustments")
      .contentType(APPLICATION_JSON).bodyValue(new StockAdjustment(-2))
      .exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.available").isEqualTo(1);

    client.post().uri("/internal/inventory/79/adjustments")
      .contentType(APPLICATION_JSON).bodyValue(new StockAdjustment(-2))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);

    client.post().uri("/internal/inventory/79/adjustments")
      .contentType(APPLICATION_JSON).bodyValue(new StockAdjustment(4))
      .exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.available").isEqualTo(5);

    client.get().uri("/product/79")
      .exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.stockQuantity").isEqualTo(5);
  }

  @Test
  void stockRequiresAnInitializedQuantity() {
    sendCreateProductEvent(80);

    client.get().uri("/internal/inventory/80")
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);

    client.post().uri("/internal/inventory/80/adjustments")
      .contentType(APPLICATION_JSON).bodyValue(new StockAdjustment(5))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
  }


  @Test
  void checkoutIsIdempotentAndCancellationRestoresStock() {
    Product p = new Product(91, "Headset", 1, null);
    p.setStockQuantity(5);
    p.setPrice(new java.math.BigDecimal("29.95"));
    p.setCurrency("USD");
    messageProcessor.accept(new Event<>(CREATE, 91, p));
    client.put().uri("/internal/carts/" + CART_OWNER + "/items/91")
      .contentType(APPLICATION_JSON).bodyValue(new QuantityRequest(2))
      .exchange().expectStatus().isOk();

    for (int i = 0; i < 2; i++) {
      client.post().uri("/internal/orders/" + CART_OWNER)
        .header("Idempotency-Key", "checkout-key-91")
        .exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.status").isEqualTo("RESERVED")
        .jsonPath("$.total").isEqualTo(59.90);
    }
    client.get().uri("/internal/inventory/91").exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.available").isEqualTo(3);
    String id = orderRepository.findAll().blockFirst().getId();
    for (int i = 0; i < 2; i++) {
      client.post().uri("/internal/orders/" + CART_OWNER + "/" + id + "/cancel")
        .exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.status").isEqualTo("CANCELLED");
    }
    client.get().uri("/internal/inventory/91").exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.available").isEqualTo(5);
    client.get().uri("/internal/orders/" + OTHER_OWNER + "/" + id)
      .exchange().expectStatus().isEqualTo(NOT_FOUND);
  }

  @Test
  void checkoutRejectsOversellingAndInvalidKeys() {
    Product p = new Product(92, "Backpack", 1, null);
    p.setStockQuantity(1);
    p.setPrice(new java.math.BigDecimal("10.00"));
    p.setCurrency("USD");
    messageProcessor.accept(new Event<>(CREATE, 92, p));
    client.put().uri("/internal/carts/" + CART_OWNER + "/items/92")
      .contentType(APPLICATION_JSON).bodyValue(new QuantityRequest(1))
      .exchange().expectStatus().isOk();

    client.post().uri("/internal/orders/" + CART_OWNER)
      .header("Idempotency-Key", "checkout-key-a")
      .exchange().expectStatus().isOk();
    client.post().uri("/internal/orders/" + CART_OWNER)
      .header("Idempotency-Key", "checkout-key-b")
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.post().uri("/internal/orders/" + CART_OWNER)
      .header("Idempotency-Key", "x")
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.get().uri("/internal/inventory/92").exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.available").isEqualTo(0);
  }


  @Test
  void expiredReservationIsReleasedOnce() {
    Product p = new Product(95, "Portable speaker", 1, null);
    p.setStockQuantity(4);
    p.setPrice(new java.math.BigDecimal("39.00"));
    p.setCurrency("USD");
    messageProcessor.accept(new Event<>(CREATE, 95, p));
    client.put().uri("/internal/carts/" + CART_OWNER + "/items/95")
      .contentType(APPLICATION_JSON).bodyValue(new QuantityRequest(2))
      .exchange().expectStatus().isOk();
    client.post().uri("/internal/orders/" + CART_OWNER)
      .header("Idempotency-Key", "expire-speaker-95")
      .exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.status").isEqualTo("RESERVED");

    OrderEntity order = orderRepository.findAll().blockFirst();
    assertNotNull(order);
    assertNotNull(order.getExpiresAt());
    order.setExpiresAt(Instant.now().minusSeconds(1));
    orderRepository.save(order).block();

    recoveryWorker.recoverOnce().block();
    assertEquals(OrderEntity.EXPIRED, orderRepository.findById(order.getId()).block().getStatus());
    assertEquals(4, repository.findByProductId(95).block().getStockQuantity());

    recoveryWorker.recoverOnce().block();
    assertEquals(4, repository.findByProductId(95).block().getStockQuantity());
    client.get().uri("/internal/orders/" + CART_OWNER + "/" + order.getId())
      .exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.status").isEqualTo("EXPIRED");
  }

  @Test
  void interruptedReservationAndCancellationAreRecovered() {
    Product p = new Product(96, "Notebook", 1, null);
    p.setStockQuantity(3);
    p.setPrice(new java.math.BigDecimal("5.00"));
    p.setCurrency("USD");
    messageProcessor.accept(new Event<>(CREATE, 96, p));
    client.put().uri("/internal/carts/" + CART_OWNER + "/items/96")
      .contentType(APPLICATION_JSON).bodyValue(new QuantityRequest(1))
      .exchange().expectStatus().isOk();
    client.post().uri("/internal/orders/" + CART_OWNER)
      .header("Idempotency-Key", "recover-notebook-96")
      .exchange().expectStatus().isOk();

    OrderEntity order = orderRepository.findAll().blockFirst();
    order.setStatus(OrderEntity.PENDING);
    orderRepository.save(order).block();
    recoveryWorker.recoverOnce().block();
    assertEquals(OrderEntity.RESERVED, orderRepository.findById(order.getId()).block().getStatus());
    assertEquals(2, repository.findByProductId(96).block().getStockQuantity());

    order = orderRepository.findById(order.getId()).block();
    order.setStatus(OrderEntity.CANCELLATION_PENDING);
    orderRepository.save(order).block();
    recoveryWorker.recoverOnce().block();
    assertEquals(OrderEntity.CANCELLED, orderRepository.findById(order.getId()).block().getStatus());
    assertEquals(3, repository.findByProductId(96).block().getStockQuantity());
  }


  @Test
  void signedMockSuccessIsIdempotentAndCannotBeCancelledOrExpired() {
    Product p = new Product(151, "Mock payment headset", 1, null);
    p.setStockQuantity(5);
    p.setPrice(new java.math.BigDecimal("35.00"));
    p.setCurrency("USD");
    messageProcessor.accept(new Event<>(CREATE, 151, p));
    client.put().uri("/internal/carts/" + CART_OWNER + "/items/151")
      .contentType(APPLICATION_JSON).bodyValue(new QuantityRequest(2))
      .exchange().expectStatus().isOk();
    client.post().uri("/internal/orders/" + CART_OWNER)
      .header("Idempotency-Key", "payment-success-key-151")
      .exchange().expectStatus().isOk();
    String id = orderRepository.findAll().blockFirst().getId();

    client.post().uri("/internal/orders/" + CART_OWNER + "/" + id + "/payment")
      .exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.status").isEqualTo("PENDING")
      .jsonPath("$.amount").isEqualTo(70.0);
    client.post().uri("/internal/orders/" + CART_OWNER + "/" + id + "/payment")
      .exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.paymentId").isEqualTo(id);

    String payload = paymentEvent("event-success-151", id, "SUCCEEDED");
    for (int i = 0; i < 2; i++) {
      sendSignedPaymentEvent(payload).expectStatus().isOk()
        .expectBody().jsonPath("$.status").isEqualTo("MOCK_PAID")
        .jsonPath("$.paymentStatus").isEqualTo("SUCCEEDED");
    }
    assertEquals(3, repository.findByProductId(151).block().getStockQuantity());
    var product = repository.findByProductId(151).block();
    assertTrue(product.getReservedOrderIds() == null || product.getReservedOrderIds().isEmpty());

    sendSignedPaymentEvent(paymentEvent("event-other-151", id, "DECLINED"))
      .expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.post().uri("/internal/orders/" + CART_OWNER + "/" + id + "/cancel")
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);

    var paid = orderRepository.findById(id).block();
    paid.setExpiresAt(Instant.now().minusSeconds(1));
    orderRepository.save(paid).block();
    recoveryWorker.recoverOnce().block();
    assertEquals("MOCK_PAID", orderRepository.findById(id).block().getStatus());
    assertEquals(3, repository.findByProductId(151).block().getStockQuantity());
  }

  @Test
  void signedMockDeclineReleasesStockOnce() {
    Product p = new Product(152, "Payment test chair", 2, null);
    p.setStockQuantity(2);
    p.setPrice(new java.math.BigDecimal("15.00"));
    p.setCurrency("USD");
    messageProcessor.accept(new Event<>(CREATE, 152, p));
    client.put().uri("/internal/carts/" + CART_OWNER + "/items/152")
      .contentType(APPLICATION_JSON).bodyValue(new QuantityRequest(1))
      .exchange().expectStatus().isOk();
    client.post().uri("/internal/orders/" + CART_OWNER)
      .header("Idempotency-Key", "payment-decline-key-152")
      .exchange().expectStatus().isOk();
    String id = orderRepository.findAll().blockFirst().getId();
    client.post().uri("/internal/orders/" + CART_OWNER + "/" + id + "/payment")
      .exchange().expectStatus().isOk();

    String event = paymentEvent("event-decline-152", id, "DECLINED");
    for (int i = 0; i < 2; i++) {
      sendSignedPaymentEvent(event).expectStatus().isOk()
        .expectBody().jsonPath("$.status").isEqualTo("PAYMENT_FAILED")
        .jsonPath("$.paymentStatus").isEqualTo("DECLINED");
    }
    assertEquals(2, repository.findByProductId(152).block().getStockQuantity());
    assertTrue(repository.findByProductId(152).block().getReservedOrderIds().isEmpty());
    recoveryWorker.recoverOnce().block();
    assertEquals(2, repository.findByProductId(152).block().getStockQuantity());
    sendSignedPaymentEvent(paymentEvent("event-another-152", id, "SUCCEEDED"))
      .expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
  }

  @Test
  void mockWebhookRejectsTamperedPayloadAndOldTimestamps() {
    String id = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    String body = paymentEvent("event-tampered-155", id, "SUCCEEDED");
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    String sig = paymentSignature(timestamp, body);

    client.post().uri("/internal/mock-payments/webhook")
      .header("X-Mock-Timestamp", timestamp)
      .header("X-Mock-Signature", sig)
      .contentType(APPLICATION_JSON)
      .bodyValue(body.replace("SUCCEEDED", "DECLINED").getBytes(StandardCharsets.UTF_8))
      .exchange().expectStatus().isUnauthorized();

    String oldTimestamp = Long.toString(Instant.now().minusSeconds(400).getEpochSecond());
    client.post().uri("/internal/mock-payments/webhook")
      .header("X-Mock-Timestamp", oldTimestamp)
      .header("X-Mock-Signature", paymentSignature(oldTimestamp, body))
      .contentType(APPLICATION_JSON).bodyValue(body.getBytes(StandardCharsets.UTF_8))
      .exchange().expectStatus().isUnauthorized();
  }


  @Test
  void lateSignedSuccessIsRejectedAfterReservationExpiry() {
    Product p = new Product(153, "Wireless mouse", 1, null);
    p.setStockQuantity(1);
    p.setPrice(new java.math.BigDecimal("15.00"));
    p.setCurrency("USD");
    messageProcessor.accept(new Event<>(CREATE, 153, p));
    client.put().uri("/internal/carts/" + CART_OWNER + "/items/153")
      .contentType(APPLICATION_JSON).bodyValue(new QuantityRequest(1))
      .exchange().expectStatus().isOk();
    client.post().uri("/internal/orders/" + CART_OWNER)
      .header("Idempotency-Key", "late-success-key-153")
      .exchange().expectStatus().isOk();
    String id = orderRepository.findAll().blockFirst().getId();
    client.post().uri("/internal/orders/" + CART_OWNER + "/" + id + "/payment")
      .exchange().expectStatus().isOk();

    var pending = orderRepository.findById(id).block();
    pending.setExpiresAt(Instant.now().minusSeconds(2));
    orderRepository.save(pending).block();

    sendSignedPaymentEvent(paymentEvent("late-success-event-153", id, "SUCCEEDED"))
      .expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    recoveryWorker.recoverOnce().block();
    assertEquals("EXPIRED", orderRepository.findById(id).block().getStatus());
    assertEquals(1, repository.findByProductId(153).block().getStockQuantity());
  }

  @Test
  void workerFinishesInterruptedPaymentConfirmation() {
    Product p = new Product(154, "Tablet stand", 1, null);
    p.setStockQuantity(3);
    p.setPrice(new java.math.BigDecimal("8.00"));
    p.setCurrency("USD");
    messageProcessor.accept(new Event<>(CREATE, 154, p));
    client.put().uri("/internal/carts/" + CART_OWNER + "/items/154")
      .contentType(APPLICATION_JSON).bodyValue(new QuantityRequest(2))
      .exchange().expectStatus().isOk();
    client.post().uri("/internal/orders/" + CART_OWNER)
      .header("Idempotency-Key", "recovery-success-key-154")
      .exchange().expectStatus().isOk();
    String id = orderRepository.findAll().blockFirst().getId();
    client.post().uri("/internal/orders/" + CART_OWNER + "/" + id + "/payment")
      .exchange().expectStatus().isOk();
    var pending = orderRepository.findById(id).block();
    pending.setStatus("PAYMENT_CONFIRMING");
    pending.setPaymentStatus("SUCCEEDED");
    pending.setPaymentEventId("recovery-event-154");
    orderRepository.save(pending).block();

    recoveryWorker.recoverOnce().block();
    assertEquals("MOCK_PAID", orderRepository.findById(id).block().getStatus());
    assertEquals(1, repository.findByProductId(154).block().getStockQuantity());
    assertTrue(repository.findByProductId(154).block().getReservedOrderIds().isEmpty());
    recoveryWorker.recoverOnce().block();
    assertEquals(1, repository.findByProductId(154).block().getStockQuantity());
  }

  private String paymentEvent(String eventId, String paymentId, String outcome) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
          new amazon.api.commerce.MockPaymentEvent(eventId, paymentId, outcome));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private String paymentSignature(String timestamp, String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(
          "mock-test-signing-secret-with-more-than-32-characters".getBytes(StandardCharsets.UTF_8),
          "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload)
          .getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private WebTestClient.ResponseSpec sendSignedPaymentEvent(String body) {
    String timestamp = Long.toString(Instant.now().getEpochSecond());
    return client.post().uri("/internal/mock-payments/webhook")
      .header("X-Mock-Timestamp", timestamp)
      .header("X-Mock-Signature", paymentSignature(timestamp, body))
      .contentType(APPLICATION_JSON).bodyValue(body.getBytes(StandardCharsets.UTF_8))
      .exchange();
  }


  @Test
  void mockShipmentRequiresPaidOrderAndIsIdempotent() {
    String orderId = String.format("%064x", 901);
    OrderEntity reserved = new OrderEntity(orderId, CART_OWNER, 901, 1,
        "Simulated parcel", new java.math.BigDecimal("42.00"), "USD");
    reserved.setStatus(OrderEntity.RESERVED);
    orderRepository.save(reserved).block();

    client.post().uri("/internal/shipments")
      .contentType(APPLICATION_JSON)
      .bodyValue(new CreateShipmentRequest(orderId, "NORTH_01"))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);

    OrderEntity paid = orderRepository.findById(orderId).block();
    paid.setStatus(OrderEntity.MOCK_PAID);
    orderRepository.save(paid).block();

    for (int i = 0; i < 2; i++) {
      client.post().uri("/internal/shipments")
        .contentType(APPLICATION_JSON)
        .bodyValue(new CreateShipmentRequest(orderId, "NORTH_01"))
        .exchange().expectStatus().isOk().expectBody()
        .jsonPath("$.status").isEqualTo("CREATED")
        .jsonPath("$.carrier").isEqualTo("MOCK_CARRIER")
        .jsonPath("$.history.length()").isEqualTo(1);
    }
    assertEquals(1L, (long) shipmentRepository.count().block());

    client.post().uri("/internal/shipments")
      .contentType(APPLICATION_JSON)
      .bodyValue(new CreateShipmentRequest(orderId, "SOUTH_02"))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);

    client.post().uri("/internal/orders/" + CART_OWNER + "/" + orderId + "/cancel")
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
  }

  @Test
  void trackingTransitionsAreOrderedAuditableAndReplaySafe() {
    String orderId = String.format("%064x", 902);
    OrderEntity paid = new OrderEntity(orderId, CART_OWNER, 902, 2,
        "Simulated keyboard", new java.math.BigDecimal("12.00"), "USD");
    paid.setStatus(OrderEntity.MOCK_PAID);
    orderRepository.save(paid).block();

    client.post().uri("/internal/shipments")
      .contentType(APPLICATION_JSON)
      .bodyValue(new CreateShipmentRequest(orderId, "EU_CENTRAL"))
      .exchange().expectStatus().isOk();

    client.post().uri("/internal/shipments/" + orderId + "/events")
      .contentType(APPLICATION_JSON)
      .bodyValue(new ShipmentStatusUpdate("event-shipped-902", "SHIPPED"))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);

    String[] statuses = {"PACKED", "SHIPPED", "IN_TRANSIT", "DELIVERED"};
    for (int i = 0; i < statuses.length; i++) {
      String eventId = "event-902-" + (i + 1);
      for (int retry = 0; retry < 2; retry++) {
        client.post().uri("/internal/shipments/" + orderId + "/events")
          .contentType(APPLICATION_JSON)
          .bodyValue(new ShipmentStatusUpdate(eventId, statuses[i]))
          .exchange().expectStatus().isOk()
          .expectBody().jsonPath("$.status").isEqualTo(statuses[i])
          .jsonPath("$.history.length()").isEqualTo(i + 2);
      }
    }

    client.post().uri("/internal/shipments/" + orderId + "/events")
      .contentType(APPLICATION_JSON)
      .bodyValue(new ShipmentStatusUpdate("event-902-2", "PACKED"))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.post().uri("/internal/shipments/" + orderId + "/events")
      .contentType(APPLICATION_JSON)
      .bodyValue(new ShipmentStatusUpdate("event-902-5", "PACKED"))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.post().uri("/internal/shipments/" + orderId + "/events")
      .contentType(APPLICATION_JSON)
      .bodyValue(new ShipmentStatusUpdate("event-902-6", null))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);

    client.get().uri("/internal/shipments/owner/" + CART_OWNER + "/order/" + orderId)
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.status").isEqualTo("DELIVERED")
      .jsonPath("$.history.length()").isEqualTo(5);
    client.get().uri("/internal/shipments/owner/" + OTHER_OWNER + "/order/" + orderId)
      .exchange().expectStatus().isEqualTo(NOT_FOUND);
    assertEquals(OrderEntity.MOCK_PAID,
        orderRepository.findById(orderId).block().getStatus());
  }

  @Test
  void nonexistentShipmentAndInvalidDestinationAreRejected() {
    String orderId = String.format("%064x", 903);
    client.post().uri("/internal/shipments")
      .contentType(APPLICATION_JSON)
      .bodyValue(new CreateShipmentRequest(orderId, "ZONE_03"))
      .exchange().expectStatus().isEqualTo(NOT_FOUND);

    OrderEntity paid = new OrderEntity(orderId, CART_OWNER, 903, 1,
        "Simulated item", new java.math.BigDecimal("9.00"), "USD");
    paid.setStatus(OrderEntity.MOCK_PAID);
    orderRepository.save(paid).block();

    client.post().uri("/internal/shipments")
      .contentType(APPLICATION_JSON)
      .bodyValue(new CreateShipmentRequest(orderId, "street address / private location"))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.get().uri("/internal/shipments/owner/" + CART_OWNER + "/order/" + orderId)
      .exchange().expectStatus().isEqualTo(NOT_FOUND);
  }


  private String deliveredMockPaidOrder(int suffix, String owner, int stock) {
    String id = String.format("%064x", suffix);
    OrderEntity order = new OrderEntity(id, owner, suffix, 2, "Mock delivered item",
        new java.math.BigDecimal("12.50"), "USD");
    order.setStatus(OrderEntity.MOCK_PAID);
    orderRepository.save(order).block();
    ShipmentEntity shipment = new ShipmentEntity(id, "NORTH_01", "MOCK-TEST");
    shipment.setStatus("DELIVERED");
    java.util.List<ShipmentEvent> history = new java.util.ArrayList<>(shipment.getHistory());
    history.add(new ShipmentEvent("delivery-event-" + suffix, "DELIVERED", Instant.now()));
    shipment.setHistory(history);
    shipmentRepository.save(shipment).block();
    return id;
  }

  @Test
  void returnLifecycleIsIdempotentAndDoesNotModifyInventory() {
    String orderId = deliveredMockPaidOrder(981, CART_OWNER, 10);
    String customer = "/internal/returns/owner/" + CART_OWNER + "/order/" + orderId;
    String operator = "/internal/returns/" + orderId;

    for (int i = 0; i < 2; i++) {
      client.post().uri(customer).contentType(APPLICATION_JSON)
        .bodyValue(new ReturnRequest("DAMAGED"))
        .exchange().expectStatus().isOk().expectBody()
        .jsonPath("$.state").isEqualTo("REQUESTED")
        .jsonPath("$.refundAmount").isEqualTo(25.0)
        .jsonPath("$.history.length()").isEqualTo(1);
    }
    assertEquals(1L, (long) returnRepository.count().block());

    String[] actions = {"approve", "receive", "mock-refund"};
    String[] states = {"APPROVED", "RECEIVED", "MOCK_REFUNDED"};
    for (int i = 0; i < actions.length; i++) {
      String eventId = "return-event-" + (981 + i);
      for (int replay = 0; replay < 2; replay++) {
        client.post().uri(operator + "/" + actions[i])
          .contentType(APPLICATION_JSON).bodyValue(new ReturnAction(eventId))
          .exchange().expectStatus().isOk().expectBody()
          .jsonPath("$.state").isEqualTo(states[i])
          .jsonPath("$.history.length()").isEqualTo(i + 2);
      }
    }
    client.get().uri(customer).exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.state").isEqualTo("MOCK_REFUNDED");
    client.get().uri("/internal/returns/owner/" + OTHER_OWNER + "/order/" + orderId)
      .exchange().expectStatus().isEqualTo(NOT_FOUND);
    assertEquals(OrderEntity.MOCK_PAID, orderRepository.findById(orderId).block().getStatus());
  }

  @Test
  void returnTransitionsEnforceOrderAndRejectConflictingEvents() {
    String id = deliveredMockPaidOrder(982, CART_OWNER, 1);
    String customer = "/internal/returns/owner/" + CART_OWNER + "/order/" + id;
    String operator = "/internal/returns/" + id;
    client.post().uri(customer).contentType(APPLICATION_JSON)
      .bodyValue(new ReturnRequest("WRONG_ITEM")).exchange().expectStatus().isOk();

    client.post().uri(operator + "/receive").contentType(APPLICATION_JSON)
      .bodyValue(new ReturnAction("return-early-982"))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.post().uri(operator + "/mock-refund").contentType(APPLICATION_JSON)
      .bodyValue(new ReturnAction("return-early-983"))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);

    client.post().uri(operator + "/reject").contentType(APPLICATION_JSON)
      .bodyValue(new ReturnAction("return-reject-982"))
      .exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.state").isEqualTo("REJECTED");

    client.post().uri(operator + "/approve").contentType(APPLICATION_JSON)
      .bodyValue(new ReturnAction("return-approve-982"))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.post().uri(operator + "/approve").contentType(APPLICATION_JSON)
      .bodyValue(new ReturnAction("return-reject-982"))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.post().uri(customer).contentType(APPLICATION_JSON)
      .bodyValue(new ReturnRequest("DAMAGED"))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
  }

  @Test
  void returnRequiresDeliveryAndRejectsOtherOwners() {
    String orderId = String.format("%064x", 983);
    OrderEntity pending = new OrderEntity(orderId, CART_OWNER, 983, 1,
      "Undelivered item", new java.math.BigDecimal("2.00"), "USD");
    pending.setStatus(OrderEntity.MOCK_PAID);
    orderRepository.save(pending).block();
    client.post().uri("/internal/returns/owner/" + CART_OWNER + "/order/" + orderId)
      .contentType(APPLICATION_JSON).bodyValue(new ReturnRequest("DAMAGED"))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.post().uri("/internal/returns/owner/" + OTHER_OWNER + "/order/" + orderId)
      .contentType(APPLICATION_JSON).bodyValue(new ReturnRequest("DAMAGED"))
      .exchange().expectStatus().isEqualTo(NOT_FOUND);

    ShipmentEntity notDelivered = new ShipmentEntity(orderId, "ZONE_1", "MOCK-TEST");
    shipmentRepository.save(notDelivered).block();
    client.post().uri("/internal/returns/owner/" + CART_OWNER + "/order/" + orderId)
      .contentType(APPLICATION_JSON).bodyValue(new ReturnRequest("DAMAGED"))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.post().uri("/internal/returns/owner/" + CART_OWNER + "/order/" + orderId)
      .contentType(APPLICATION_JSON).bodyValue(new ReturnRequest("arbitrary free-text PII"))
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
  }


  @Test
  void wishlistIsOwnerScopedAndSavingSameItemIsIdempotent() {
    Product product = new Product(210, "Noise cancelling headset", 2, null);
    product.setCategory("audio");
    product.setStockQuantity(0);
    product.setPrice(new java.math.BigDecimal("49.00"));
    product.setCurrency("USD");
    messageProcessor.accept(new Event<>(CREATE, 210, product));
    String path = "/internal/wishlists/" + CART_OWNER;

    for (int i = 0; i < 2; i++) {
      client.put().uri(path + "/items/210")
        .exchange().expectStatus().isOk().expectBody()
        .jsonPath("$.totalElements").isEqualTo(1)
        .jsonPath("$.items[0].productId").isEqualTo(210)
        .jsonPath("$.items[0].inStock").isEqualTo(false)
        .jsonPath("$.items[0].price").isEqualTo(49.0);
    }
    assertEquals(1L, (long) wishlistRepository.count().block());
    client.get().uri("/internal/wishlists/" + OTHER_OWNER)
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.totalElements").isEqualTo(0)
      .jsonPath("$.items.length()").isEqualTo(0);

    client.delete().uri(path + "/items/210")
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.totalElements").isEqualTo(0);
    client.delete().uri(path + "/items/210")
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.totalElements").isEqualTo(0);
  }

  @Test
  void wishlistShowsLivePriceStockAndDeletedProductState() {
    Product product = new Product(211, "Travel backpack", 1, null);
    product.setStockQuantity(1);
    product.setPrice(new java.math.BigDecimal("15.00"));
    product.setCurrency("USD");
    messageProcessor.accept(new Event<>(CREATE, 211, product));
    String path = "/internal/wishlists/" + CART_OWNER;
    client.put().uri(path + "/items/211").exchange().expectStatus().isOk();

    var entity = repository.findByProductId(211).block();
    entity.setStockQuantity(0);
    entity.setPrice(new java.math.BigDecimal("25.00"));
    repository.save(entity).block();

    client.get().uri(path).exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.items[0].inStock").isEqualTo(false)
      .jsonPath("$.items[0].price").isEqualTo(25.0);

    repository.delete(repository.findByProductId(211).block()).block();
    client.get().uri(path).exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.items[0].productExists").isEqualTo(false)
      .jsonPath("$.items[0].inStock").isEqualTo(false)
      .jsonPath("$.items[0].productId").isEqualTo(211);
    client.put().uri(path + "/items/211")
      .exchange().expectStatus().isEqualTo(NOT_FOUND);
  }

  @Test
  void wishlistPagesAndClearAreBounded() {
    String path = "/internal/wishlists/" + CART_OWNER;
    for (int id = 220; id < 223; id++) {
      messageProcessor.accept(new Event<>(CREATE, id, new Product(id, "Saved " + id, 1, null)));
      client.put().uri(path + "/items/" + id).exchange().expectStatus().isOk();
    }
    client.get().uri(path + "?page=0&size=2").exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.totalElements").isEqualTo(3)
      .jsonPath("$.totalPages").isEqualTo(2)
      .jsonPath("$.items.length()").isEqualTo(2);
    client.get().uri(path + "?page=1&size=2").exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.items.length()").isEqualTo(1);
    client.get().uri(path + "?page=5&size=2").exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.items.length()").isEqualTo(0);
    client.get().uri(path + "?page=-1").exchange()
      .expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.get().uri(path + "?size=101").exchange()
      .expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);

    client.delete().uri(path).exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.totalElements").isEqualTo(0);
    client.delete().uri(path).exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.totalElements").isEqualTo(0);
    assertEquals(0L, (long) cartRepository.count().block());
  }


  @Test
  void catalogDiscoveryFiltersInStockSortsAndEscapesLiteralSearch() {
    Product p1 = new Product(301, "Laptop 16", 2, null);
    p1.setCategory("computers");
    p1.setStockQuantity(2);
    Product p2 = new Product(302, "Laptop 14", 2, null);
    p2.setCategory("computers");
    p2.setStockQuantity(0);
    Product p3 = new Product(303, "Laptop Pro", 2, null);
    p3.setCategory("computers");
    p3.setStockQuantity(5);
    Product p4 = new Product(304, "Tablet", 1, null);
    p4.setCategory("electronics");
    p4.setStockQuantity(3);
    for (Product p : java.util.List.of(p1, p2, p3, p4))
      messageProcessor.accept(new Event<>(CREATE, p.getProductId(), p));

    client.get().uri("/products?query=laptop&category=computers&inStock=true&sort=stockDesc&size=10")
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.totalElements").isEqualTo(2)
      .jsonPath("$.products[0].productId").isEqualTo(303)
      .jsonPath("$.products[1].productId").isEqualTo(301);

    client.get().uri("/products?category=computers&inStock=false&size=10")
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.products[0].productId").isEqualTo(302);

    client.get().uri("/products?query=%5C%5C%7C&size=10")
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.totalElements").isEqualTo(0);
    client.get().uri("/products?sort=priceDesc")
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
  }

  @Test
  void relatedCategoryDiscoveryExcludesSourceAndHandlesMissingCategory() {
    Product source = new Product(311, "Outdoor jacket", 2, null);
    source.setCategory("apparel");
    Product relatedA = new Product(312, "Rain jacket", 1, null);
    relatedA.setCategory("apparel");
    Product relatedB = new Product(313, "Coat", 1, null);
    relatedB.setCategory("apparel");
    Product other = new Product(314, "Phone", 1, null);
    other.setCategory("electronics");
    for (Product p : java.util.List.of(source, relatedA, relatedB, other))
      messageProcessor.accept(new Event<>(CREATE, p.getProductId(), p));

    client.get().uri("/products/311/related?limit=1")
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.basis").isEqualTo("shared-category")
      .jsonPath("$.products.length()").isEqualTo(1)
      .jsonPath("$.products[0].productId").isEqualTo(312);
    client.get().uri("/products/311/related?limit=30")
      .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    client.get().uri("/products/999/related")
      .exchange().expectStatus().isEqualTo(NOT_FOUND);
  }


  @Test
  void reviewPurchaseEligibilityRequiresCorrectOwnerProductAndMockPaidOrder() {
    String id = String.format("%064x", 601);
    OrderEntity order = new OrderEntity(id, CART_OWNER, 601, 1,
      "Simulated purchased product", new java.math.BigDecimal("10.00"), "USD");
    order.setStatus(OrderEntity.RESERVED);
    orderRepository.save(order).block();

    client.get().uri("/internal/review-eligibility/" + CART_OWNER + "/product/601")
      .exchange().expectStatus().isOk().expectBody(Boolean.class).isEqualTo(false);
    order = orderRepository.findById(id).block();
    order.setStatus(OrderEntity.MOCK_PAID);
    orderRepository.save(order).block();

    client.get().uri("/internal/review-eligibility/" + CART_OWNER + "/product/601")
      .exchange().expectStatus().isOk().expectBody(Boolean.class).isEqualTo(true);
    client.get().uri("/internal/review-eligibility/" + OTHER_OWNER + "/product/601")
      .exchange().expectStatus().isOk().expectBody(Boolean.class).isEqualTo(false);
    client.get().uri("/internal/review-eligibility/" + CART_OWNER + "/product/602")
      .exchange().expectStatus().isOk().expectBody(Boolean.class).isEqualTo(false);
  }


  @Test
  void orderHistoryUsesOwnerScopeAndStableNewestFirstPagination() {
    Instant origin = Instant.parse("2026-11-01T00:00:00Z");
    for (int i = 0; i < 4; i++) {
      String id = String.format("%064x", 801 + i);
      String owner = i == 3 ? OTHER_OWNER : CART_OWNER;
      OrderEntity order = new OrderEntity(id, owner, 801 + i, 1,
          "History item " + i, new java.math.BigDecimal("15.00"), "USD");
      order.setStatus(i == 1 ? OrderEntity.CANCELLED : OrderEntity.MOCK_PAID);
      order.setCreatedAt(origin.plusSeconds(i * 60L));
      orderRepository.save(order).block();
    }
    String url = "/internal/order-history/" + CART_OWNER;
    client.get().uri(url + "?page=0&size=2").exchange().expectStatus().isOk()
      .expectBody()
      .jsonPath("$.totalElements").isEqualTo(3)
      .jsonPath("$.totalPages").isEqualTo(2)
      .jsonPath("$.orders.length()").isEqualTo(2)
      .jsonPath("$.orders[0].order.productId").isEqualTo(803)
      .jsonPath("$.orders[1].order.productId").isEqualTo(802);
    client.get().uri(url + "?page=1&size=2").exchange().expectStatus().isOk()
      .expectBody()
      .jsonPath("$.orders.length()").isEqualTo(1)
      .jsonPath("$.orders[0].order.productId").isEqualTo(801);
    client.get().uri(url + "?page=0&size=10&status=MOCK_PAID")
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.totalElements").isEqualTo(2);
    client.get().uri("/internal/order-history/" + OTHER_OWNER)
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.totalElements").isEqualTo(1)
      .jsonPath("$.orders[0].order.productId").isEqualTo(804);
  }

  @Test
  void orderHistoryIncludesShipmentAndReturnAndObservedTimelineOnly() {
    String id = String.format("%064x", 811);
    Instant created = Instant.parse("2026-10-01T10:00:00Z");
    OrderEntity order = new OrderEntity(id, CART_OWNER, 811, 2,
        "Combined order", new java.math.BigDecimal("25.00"), "USD");
    order.setStatus(OrderEntity.MOCK_PAID);
    order.setCreatedAt(created);
    order.setPaymentStartedAt(created.plusSeconds(60));
    order.setMockPaidAt(created.plusSeconds(120));
    orderRepository.save(order).block();

    ShipmentEntity shipment = new ShipmentEntity(id, "TEST_ZONE", "MOCK-811");
    shipment.setStatus("DELIVERED");
    java.util.List<ShipmentEvent> shipmentHistory = new java.util.ArrayList<>(shipment.getHistory());
    shipmentHistory.add(new ShipmentEvent("event-delivered-811", "DELIVERED", created.plusSeconds(240)));
    shipment.setHistory(shipmentHistory);
    shipmentRepository.save(shipment).block();

    ReturnEntity returned = new ReturnEntity(id, CART_OWNER, "DAMAGED",
        new java.math.BigDecimal("50.00"), "USD");
    returnRepository.save(returned).block();

    String url = "/internal/order-history/" + CART_OWNER;
    client.get().uri(url + "?size=5").exchange().expectStatus().isOk()
      .expectBody()
      .jsonPath("$.orders[0].order.total").isEqualTo(50.0)
      .jsonPath("$.orders[0].shipment.status").isEqualTo("DELIVERED")
      .jsonPath("$.orders[0].shipment.trackingNumber").isEqualTo("MOCK-811")
      .jsonPath("$.orders[0].returnSummary.state").isEqualTo("REQUESTED");

    client.get().uri(url + "/orders/" + id + "/timeline")
      .exchange().expectStatus().isOk().expectBody()
      .jsonPath("$.order.orderId").isEqualTo(id)
      .jsonPath("$.events.length()").isEqualTo(6)
      .jsonPath("$.events[0].source").isEqualTo("ORDER")
      .jsonPath("$.events[0].status").isEqualTo("CREATED")
      .jsonPath("$.events[1].status").isEqualTo("MOCK_PAYMENT_STARTED")
      .jsonPath("$.events[2].status").isEqualTo("MOCK_PAID");

    client.get().uri("/internal/order-history/" + OTHER_OWNER + "/orders/" + id + "/timeline")
      .exchange().expectStatus().isEqualTo(NOT_FOUND);
  }

  @Test
  void orderHistoryRejectsInvalidPagingAndStatusAndReturnsEmptyResults() {
    String url = "/internal/order-history/" + CART_OWNER;
    client.get().uri(url).exchange().expectStatus().isOk()
      .expectBody().jsonPath("$.totalElements").isEqualTo(0)
      .jsonPath("$.orders.length()").isEqualTo(0);
    for (String query : new String[] {"?page=-1", "?size=0", "?size=51",
                                      "?page=2147483647&size=50",
                                      "?status=INVALID_STATE"}) {
      client.get().uri(url + query).exchange()
        .expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    }
    client.get().uri(url + "/orders/" + String.format("%064x", 999) + "/timeline")
      .exchange().expectStatus().isEqualTo(NOT_FOUND);
  }

  private void sendCreateProductEvent(int productId) {
    Product product = new Product(productId, "Name " + productId, productId, "SA");
    Event<Integer, Product> event = new Event(CREATE, productId, product);
    messageProcessor.accept(event);
  }

  private void sendDeleteProductEvent(int productId) {
    Event<Integer, Product> event = new Event(DELETE, productId, null);
    messageProcessor.accept(event);
  }
}
