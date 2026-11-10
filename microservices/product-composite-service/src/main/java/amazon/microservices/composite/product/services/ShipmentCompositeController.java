package amazon.microservices.composite.product.services;

import amazon.api.commerce.CreateShipmentRequest;
import amazon.api.commerce.ShipmentDetails;
import amazon.api.commerce.ShipmentStatusUpdate;
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
 * Customer tracking and scope-protected mock carrier operations.
 * Do not proxy client-supplied owner IDs to the internal service.
 */
@RestController
@RequestMapping("/product-composite")
public class ShipmentCompositeController {
    private final WebClient client;

    public ShipmentCompositeController(WebClient client) {
        this.client = client;
    }

    @GetMapping(value = "/orders/{orderId}/shipment", produces = "application/json")
    public Mono<ShipmentDetails> track(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable String orderId) {
        String owner = CommerceCompositeController.ownerKey(jwt);
        return client.get().uri("http://product/internal/shipments/owner/{owner}/order/{orderId}",
                        owner, orderId)
                .retrieve().bodyToMono(ShipmentDetails.class).onErrorMap(this::mapError);
    }

    @PostMapping(value = "/fulfillment/shipments", consumes = "application/json",
            produces = "application/json")
    public Mono<ShipmentDetails> create(@RequestBody CreateShipmentRequest request) {
        return client.post().uri("http://product/internal/shipments")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .retrieve().bodyToMono(ShipmentDetails.class).onErrorMap(this::mapError);
    }

    @GetMapping(value = "/fulfillment/shipments/{orderId}", produces = "application/json")
    public Mono<ShipmentDetails> operatorTrack(@PathVariable String orderId) {
        return client.get().uri("http://product/internal/shipments/{orderId}", orderId)
                .retrieve().bodyToMono(ShipmentDetails.class).onErrorMap(this::mapError);
    }

    @PostMapping(value = "/fulfillment/shipments/{orderId}/events",
            consumes = "application/json", produces = "application/json")
    public Mono<ShipmentDetails> advance(@PathVariable String orderId,
                                         @RequestBody ShipmentStatusUpdate update) {
        return client.post().uri("http://product/internal/shipments/{orderId}/events", orderId)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(update)
                .retrieve().bodyToMono(ShipmentDetails.class).onErrorMap(this::mapError);
    }

    private Throwable mapError(Throwable error) {
        if (error instanceof WebClientResponseException response) {
            HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
            if (status != null) return new ResponseStatusException(status, "Shipment operation rejected");
        }
        return error;
    }
}
