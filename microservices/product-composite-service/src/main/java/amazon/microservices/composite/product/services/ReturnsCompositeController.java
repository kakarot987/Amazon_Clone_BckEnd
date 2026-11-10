package amazon.microservices.composite.product.services;

import amazon.api.commerce.ReturnAction;
import amazon.api.commerce.ReturnReceipt;
import amazon.api.commerce.ReturnRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** JWT-scoped customer return requests and privileged mock-return operations. */
@RestController
@RequestMapping("/product-composite")
public class ReturnsCompositeController {
    private final WebClient client;

    public ReturnsCompositeController(WebClient client) {
        this.client = client;
    }

    @PostMapping(value = "/orders/{orderId}/return", consumes = "application/json",
            produces = "application/json")
    public Mono<ReturnReceipt> request(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable String orderId, @RequestBody ReturnRequest request) {
        String owner = CommerceCompositeController.ownerKey(jwt);
        return client.post().uri("http://product/internal/returns/owner/{owner}/order/{id}", owner, orderId)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .retrieve().bodyToMono(ReturnReceipt.class).onErrorMap(this::mapError);
    }

    @GetMapping(value = "/orders/{orderId}/return", produces = "application/json")
    public Mono<ReturnReceipt> customerRead(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable String orderId) {
        String owner = CommerceCompositeController.ownerKey(jwt);
        return client.get().uri("http://product/internal/returns/owner/{owner}/order/{id}", owner, orderId)
                .retrieve().bodyToMono(ReturnReceipt.class).onErrorMap(this::mapError);
    }

    @GetMapping(value = "/returns/{orderId}", produces = "application/json")
    public Mono<ReturnReceipt> operatorRead(@PathVariable String orderId) {
        return client.get().uri("http://product/internal/returns/{id}", orderId)
                .retrieve().bodyToMono(ReturnReceipt.class).onErrorMap(this::mapError);
    }

    @PostMapping(value = "/returns/{orderId}/{action}", consumes = "application/json",
            produces = "application/json")
    public Mono<ReturnReceipt> operatorAction(@PathVariable String orderId,
                                               @PathVariable String action,
                                               @RequestBody ReturnAction request) {
        if (!java.util.Set.of("approve", "reject", "receive", "mock-refund").contains(action)) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unknown return action"));
        }
        return client.post().uri("http://product/internal/returns/{id}/{action}", orderId, action)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .retrieve().bodyToMono(ReturnReceipt.class).onErrorMap(this::mapError);
    }

    private Throwable mapError(Throwable error) {
        if (error instanceof WebClientResponseException response) {
            HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
            if (status != null) return new ResponseStatusException(status, "Return operation rejected");
        }
        return error;
    }
}
