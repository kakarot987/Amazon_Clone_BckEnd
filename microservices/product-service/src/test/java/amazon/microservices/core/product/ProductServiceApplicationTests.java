package amazon.microservices.core.product;

import amazon.api.core.product.Product;
import amazon.api.event.Event;
import amazon.microservices.core.product.persistence.ProductRepository;
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
  @Qualifier("messageProcessor")
  private Consumer<Event<Integer, Product>> messageProcessor;

  @BeforeEach
  void setupDb() {
    repository.deleteAll().block();
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
