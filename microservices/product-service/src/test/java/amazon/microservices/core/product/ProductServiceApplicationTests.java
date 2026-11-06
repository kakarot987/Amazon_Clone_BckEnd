package amazon.microservices.core.product;

import amazon.api.core.product.Product;
import amazon.api.event.Event;
import amazon.microservices.core.product.persistence.ProductRepository;
import amazon.microservices.core.product.persistence.CartRepository;
import amazon.microservices.core.product.persistence.OrderRepository;
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

import static amazon.api.event.Event.Type.CREATE;
import static amazon.api.event.Event.Type.DELETE;
import static amazon.api.event.Event.Type.UPDATE;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {
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
  @Qualifier("messageProcessor")
  private Consumer<Event<Integer, Product>> messageProcessor;

  @BeforeEach
  void setupDb() {
    repository.deleteAll().block();
    cartRepository.deleteAll().block();
    orderRepository.deleteAll().block();
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
