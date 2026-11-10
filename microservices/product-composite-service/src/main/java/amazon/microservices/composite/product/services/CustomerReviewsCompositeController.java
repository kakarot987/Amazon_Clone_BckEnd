package amazon.microservices.composite.product.services;

import amazon.api.discovery.CustomerReview;
import amazon.api.discovery.CustomerReviewPage;
import amazon.api.discovery.CustomerReviewRequest;
import amazon.api.discovery.RatingSummary;
import amazon.util.exceptions.InvalidInputException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Public customer reviews and rating summary.
 * Writes derive owner from validated JWT and check product existence.
 */
@RestController
@RequestMapping("/product-composite/products/{productId}")
public class CustomerReviewsCompositeController {
    private final WebClient webClient;

    public CustomerReviewsCompositeController(WebClient webClient) {
        this.webClient = webClient;
    }

    @GetMapping(value = "/reviews", produces = "application/json")
    public Mono<CustomerReviewPage> list(@PathVariable int productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "newest") String sort) {
        validateProductId(productId);
        return webClient.get().uri(builder -> builder.scheme("http").host("review")
                        .path("/internal/customer-reviews/{id}")
                        .queryParam("page", page).queryParam("size", size)
                        .queryParam("sort", sort).build(productId))
                .retrieve().bodyToMono(CustomerReviewPage.class).onErrorMap(this::mapError);
    }

    @GetMapping(value = "/ratings", produces = "application/json")
    public Mono<RatingSummary> summary(@PathVariable int productId) {
        validateProductId(productId);
        return webClient.get().uri("http://review/internal/customer-reviews/{id}/ratings", productId)
                .retrieve().bodyToMono(RatingSummary.class).onErrorMap(this::mapError);
    }

    @PutMapping(value = "/my-review", consumes = "application/json", produces = "application/json")
    public Mono<CustomerReview> write(@AuthenticationPrincipal Jwt jwt, @PathVariable int productId,
                                       @RequestBody CustomerReviewRequest request) {
        String owner = CommerceCompositeController.ownerKey(jwt);
        validateProductId(productId);
        // Do not create reviews for missing products; internal review service
        // does not have direct access to the product catalog.
        return webClient.get().uri("http://product/product/{id}", productId)
                .retrieve().bodyToMono(amazon.api.core.product.Product.class)
                .onErrorMap(this::mapError)
                .then(webClient.put()
                        .uri("http://review/internal/customer-reviews/{id}/owner/{owner}", productId, owner)
                        .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                        .retrieve().bodyToMono(CustomerReview.class).onErrorMap(this::mapError));
    }

    @DeleteMapping(value = "/my-review")
    public Mono<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable int productId) {
        String owner = CommerceCompositeController.ownerKey(jwt);
        validateProductId(productId);
        return webClient.delete()
                .uri("http://review/internal/customer-reviews/{id}/owner/{owner}", productId, owner)
                .retrieve().bodyToMono(Void.class).onErrorMap(this::mapError);
    }

    private void validateProductId(int id) {
        if (id < 1) throw new InvalidInputException("Invalid productId");
    }

    private Throwable mapError(Throwable error) {
        if (error instanceof WebClientResponseException response) {
            HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
            if (status != null) return new ResponseStatusException(status, "Review operation rejected");
        }
        return error;
    }
}
