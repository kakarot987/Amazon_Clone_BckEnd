package amazon.microservices.composite.product.services;

import amazon.api.commerce.OrderHistoryPage;
import amazon.api.commerce.OrderTimeline;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Read-only customer order history. Owner is always derived from the JWT;
 * the caller may never provide or substitute an account identifier.
 */
@RestController
@RequestMapping("/product-composite/orders")
public class OrderHistoryCompositeController {
    private final WebClient client;

    public OrderHistoryCompositeController(WebClient client) {
        this.client = client;
    }

    @GetMapping(value = "/history", produces = "application/json")
    public Mono<OrderHistoryPage> list(@AuthenticationPrincipal Jwt jwt,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size,
                                        @RequestParam(required = false) String status) {
        String owner = CommerceCompositeController.ownerKey(jwt);
        return client.get().uri(builder -> {
                    builder.scheme("http").host("product")
                            .path("/internal/order-history/{owner}")
                            .queryParam("page", page).queryParam("size", size);
                    if (status != null) builder.queryParam("status", status);
                    return builder.build(owner);
                }).retrieve().bodyToMono(OrderHistoryPage.class)
                .onErrorMap(this::mapError);
    }

    @GetMapping(value = "/{orderId}/timeline", produces = "application/json")
    public Mono<OrderTimeline> timeline(@AuthenticationPrincipal Jwt jwt,
                                         @PathVariable String orderId) {
        String owner = CommerceCompositeController.ownerKey(jwt);
        return client.get().uri(
                        "http://product/internal/order-history/{owner}/orders/{id}/timeline",
                        owner, orderId)
                .retrieve().bodyToMono(OrderTimeline.class).onErrorMap(this::mapError);
    }

    private Throwable mapError(Throwable error) {
        if (error instanceof WebClientResponseException response) {
            HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
            if (status != null)
                return new ResponseStatusException(status, "Order history request rejected");
        }
        return error;
    }
}
