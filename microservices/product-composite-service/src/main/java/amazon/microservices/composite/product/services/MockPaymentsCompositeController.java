package amazon.microservices.composite.product.services;

import amazon.api.commerce.OrderReceipt;
import amazon.api.commerce.PaymentIntent;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Routes simulated payments; the customer identity is never user-supplied. */
@RestController
@RequestMapping("/product-composite/payments")
public class MockPaymentsCompositeController {
    private final WebClient client;

    public MockPaymentsCompositeController(WebClient client) {
        this.client = client;
    }

    @PostMapping(value = "/orders/{orderId}/intent", produces = "application/json")
    public Mono<PaymentIntent> startPayment(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderId) {
        String owner = CommerceCompositeController.ownerKey(jwt);
        return client.post().uri("http://product/internal/orders/{owner}/{id}/payment", owner, orderId)
                .retrieve().bodyToMono(PaymentIntent.class).onErrorMap(this::mapError);
    }

    /**
     * Open only for HMAC-signed mock-provider callbacks. Forward the exact raw
     * body and signature; the internal product service verifies both.
     */
    @PostMapping(value = "/mock-webhook", consumes = "application/json", produces = "application/json")
    public Mono<OrderReceipt> mockWebhook(@RequestHeader("X-Mock-Timestamp") String timestamp,
                                           @RequestHeader("X-Mock-Signature") String signature,
                                           @RequestBody String rawBody) {
        return client.post().uri("http://product/internal/mock-payments/webhook")
                .header("X-Mock-Timestamp", timestamp)
                .header("X-Mock-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(rawBody)
                .retrieve().bodyToMono(OrderReceipt.class).onErrorMap(this::mapError);
    }

    private Throwable mapError(Throwable error) {
        if (error instanceof WebClientResponseException response) {
            HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
            if (status != null)
                return new ResponseStatusException(status, "Mock payment operation rejected");
        }
        return error;
    }
}
