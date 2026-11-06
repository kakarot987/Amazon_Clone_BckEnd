package amazon.microservices.composite.product.services;

import amazon.api.commerce.OrderReceipt;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpStatus;

/** Authenticated single-item checkout; the caller cannot supply owner identity. */
@RestController
@RequestMapping("/product-composite/orders")
public class OrdersCompositeController {
    private final WebClient client;

    public OrdersCompositeController(WebClient client) {
        this.client = client;
    }

    @PostMapping(produces = "application/json")
    public Mono<OrderReceipt> checkout(@AuthenticationPrincipal Jwt jwt,
                                       @RequestHeader("Idempotency-Key") String idempotencyKey) {
        String owner = CommerceCompositeController.ownerKey(jwt);
        return client.post().uri("http://product/internal/orders/{owner}", owner)
                .header("Idempotency-Key", idempotencyKey)
                .retrieve().bodyToMono(OrderReceipt.class)
                .onErrorMap(this::mapError);
    }

    @GetMapping(value = "/{orderId}", produces = "application/json")
    public Mono<OrderReceipt> getOrder(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable String orderId) {
        String owner = CommerceCompositeController.ownerKey(jwt);
        return client.get().uri("http://product/internal/orders/{owner}/{id}", owner, orderId)
                .retrieve().bodyToMono(OrderReceipt.class).onErrorMap(this::mapError);
    }

    @PostMapping(value = "/{orderId}/cancel", produces = "application/json")
    public Mono<OrderReceipt> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderId) {
        String owner = CommerceCompositeController.ownerKey(jwt);
        return client.post().uri("http://product/internal/orders/{owner}/{id}/cancel", owner, orderId)
                .retrieve().bodyToMono(OrderReceipt.class).onErrorMap(this::mapError);
    }

    private Throwable mapError(Throwable error) {
        if (error instanceof WebClientResponseException response) {
            HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
            if (status != null) return new ResponseStatusException(status, "Order operation rejected");
        }
        return error;
    }
}
