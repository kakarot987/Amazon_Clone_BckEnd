package amazon.microservices.composite.product;

import amazon.microservices.composite.product.services.ProductCompositeIntegration;
import amazon.api.core.product.ProductPage;
import amazon.api.discovery.RelatedProducts;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import amazon.api.core.product.Product;
import amazon.api.core.recommendation.Recommendation;
import amazon.api.core.review.Review;
import amazon.util.exceptions.InvalidInputException;
import amazon.util.exceptions.NotFoundException;

import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@SpringBootTest(
		webEnvironment = RANDOM_PORT,
		classes = {TestSecurityConfig.class},
		properties = {
				"spring.security.oauth2.resourceserver.jwt.issuer-uri=",
				"spring.main.allow-bean-definition-overriding=true",
				"eureka.client.enabled=false",
				"spring.cloud.config.enabled=false","spring.cloud.compatibility-verifier.enabled=false",
                "app.outbox.initial-delay-ms=600000"})
public class ProductCompositeServiceApplicationTests {

	private static final int PRODUCT_ID_OK = 1;
	private static final int PRODUCT_ID_NOT_FOUND = 2;
	private static final int PRODUCT_ID_INVALID = 3;

    @Autowired
    private WebTestClient client;

	@MockBean
	private ProductCompositeIntegration compositeIntegration;

	@BeforeEach
	public void setUp() {

		when(compositeIntegration.getProduct(eq(PRODUCT_ID_OK), anyInt(), anyInt()))
				.thenReturn(Mono.just(new Product(PRODUCT_ID_OK, "name", 1, "mock-address")));

		when(compositeIntegration.getRecommendations(PRODUCT_ID_OK))
				.thenReturn(Flux.fromIterable(singletonList(new Recommendation(PRODUCT_ID_OK, 1, "author", 1, "content", "mock address"))));

		when(compositeIntegration.getReviews(PRODUCT_ID_OK))
				.thenReturn(Flux.fromIterable(singletonList(new Review(PRODUCT_ID_OK, 1, "author", "subject", "content", "mock address"))));

		when(compositeIntegration.getProduct(eq(PRODUCT_ID_NOT_FOUND), anyInt(), anyInt())).thenThrow(new NotFoundException("NOT FOUND: " + PRODUCT_ID_NOT_FOUND));

		when(compositeIntegration.getProduct(eq(PRODUCT_ID_INVALID), anyInt(), anyInt())).thenThrow(new InvalidInputException("INVALID: " + PRODUCT_ID_INVALID));
	}

	@Test
	void contextLoads() {
	}

	@Test
	void getProductById() {

		getAndVerifyProduct(PRODUCT_ID_OK, OK)
				.jsonPath("$.productId").isEqualTo(PRODUCT_ID_OK)
				.jsonPath("$.recommendations.length()").isEqualTo(1)
				.jsonPath("$.reviews.length()").isEqualTo(1);
	}

	@Test
	void getProductNotFound() {

		getAndVerifyProduct(PRODUCT_ID_NOT_FOUND, NOT_FOUND)
				.jsonPath("$.path").isEqualTo("/product-composite/" + PRODUCT_ID_NOT_FOUND)
				.jsonPath("$.message").isEqualTo("NOT FOUND: " + PRODUCT_ID_NOT_FOUND);
	}

	@Test
	void getProductInvalidInput() {

		getAndVerifyProduct(PRODUCT_ID_INVALID, UNPROCESSABLE_ENTITY)
				.jsonPath("$.path").isEqualTo("/product-composite/" + PRODUCT_ID_INVALID)
				.jsonPath("$.message").isEqualTo("INVALID: " + PRODUCT_ID_INVALID);
	}

    @Test
    void listProductsReturnsPagingMetadata() {
        Product item = new Product(41, "Headphones", 1, "internal");
        when(compositeIntegration.listProducts(eq("head"), eq("audio"), eq(0), eq(10)))
            .thenReturn(Mono.just(new ProductPage(List.of(item), 0, 10, 1)));
        client.get().uri("/product-composite?query=head&category=audio&page=0&size=10")
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.totalElements").isEqualTo(1)
            .jsonPath("$.products[0].productId").isEqualTo(41);
    }

    @Test
    void listRejectsInvalidPageSize() {
        client.get().uri("/product-composite?page=0&size=101")
            .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    }

    @Test
    void updatePublishesProductEvent() {
        Product product = new Product(41, "Headphones", 1, null);
        when(compositeIntegration.updateProduct(any(Product.class))).thenReturn(Mono.empty());
        client.put().uri("/product-composite/41")
            .contentType(APPLICATION_JSON).bodyValue(product)
            .exchange().expectStatus().isEqualTo(ACCEPTED);
        verify(compositeIntegration).updateProduct(any(Product.class));
    }


    @Test
    void listProductsSupportsStockAndSorting() {
        Product item = new Product(41, "Headphones", 1, null);
        when(compositeIntegration.listProducts(eq("head"), eq("audio"),
                eq(true), eq("stockDesc"), eq(0), eq(5)))
            .thenReturn(Mono.just(new ProductPage(List.of(item), 0, 5, 1)));
        client.get().uri("/product-composite?query=head&category=audio&inStock=true&sort=stockDesc&page=0&size=5")
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.products[0].productId").isEqualTo(41);

        client.get().uri("/product-composite?sort=priceDesc")
            .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    }

    @Test
    void exposesBoundedRelatedItems() {
        Product other = new Product(42, "Similar item", 1, null);
        when(compositeIntegration.relatedProducts(eq(41), eq(3)))
            .thenReturn(Mono.just(new RelatedProducts(41, "shared-category", List.of(other))));
        client.get().uri("/product-composite/products/41/related?limit=3")
            .exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.basis").isEqualTo("shared-category")
            .jsonPath("$.products[0].productId").isEqualTo(42);
        client.get().uri("/product-composite/products/41/related?limit=100")
            .exchange().expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    }

	private WebTestClient.BodyContentSpec getAndVerifyProduct(int productId, HttpStatus expectedStatus) {
		return client.get()
				.uri("/product-composite/" + productId)
				.accept(APPLICATION_JSON)
				.exchange()
				.expectStatus().isEqualTo(expectedStatus)
				.expectHeader().contentType(APPLICATION_JSON)
				.expectBody();
	}
}
