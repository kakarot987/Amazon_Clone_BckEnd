package amazon.microservices.composite.product.services;

import amazon.api.commerce.InventoryLevel;
import amazon.api.commerce.QuantityRequest;
import amazon.api.commerce.ShoppingCart;
import amazon.api.commerce.StockAdjustment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Customer-facing endpoints. Owner identity comes exclusively from a
 * verified JWT; callers cannot select or enumerate another user's cart.
 */
@RestController
@RequestMapping("/product-composite")
public class CommerceCompositeController {
    private final WebClient client;

    public CommerceCompositeController(WebClient client) {
        this.client = client;
    }

    @GetMapping(value = "/cart", produces = "application/json")
    public Mono<ShoppingCart> getCart(@AuthenticationPrincipal Jwt jwt) {
        String owner = ownerKey(jwt);
        return client.get().uri("http://product/internal/carts/{ownerKey}", owner)
                .retrieve().bodyToMono(ShoppingCart.class).onErrorMap(this::mapUpstreamError);
    }

    @PutMapping(value = "/cart/items/{productId}", consumes = "application/json",
            produces = "application/json")
    public Mono<ShoppingCart> setItem(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable int productId, @RequestBody QuantityRequest body) {
        String owner = ownerKey(jwt);
        return client.put().uri("http://product/internal/carts/{ownerKey}/items/{productId}", owner, productId)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .retrieve().bodyToMono(ShoppingCart.class).onErrorMap(this::mapUpstreamError);
    }

    @DeleteMapping(value = "/cart/items/{productId}", produces = "application/json")
    public Mono<ShoppingCart> removeItem(@AuthenticationPrincipal Jwt jwt, @PathVariable int productId) {
        String owner = ownerKey(jwt);
        return client.delete().uri("http://product/internal/carts/{ownerKey}/items/{productId}", owner, productId)
                .retrieve().bodyToMono(ShoppingCart.class).onErrorMap(this::mapUpstreamError);
    }

    @GetMapping(value = "/inventory/{productId}", produces = "application/json")
    public Mono<InventoryLevel> getInventory(@PathVariable int productId) {
        return client.get().uri("http://product/internal/inventory/{productId}", productId)
                .retrieve().bodyToMono(InventoryLevel.class).onErrorMap(this::mapUpstreamError);
    }

    @PostMapping(value = "/inventory/{productId}/adjustments",
            consumes = "application/json", produces = "application/json")
    public Mono<InventoryLevel> adjustInventory(@PathVariable int productId,
                                                @RequestBody StockAdjustment adjustment) {
        return client.post().uri("http://product/internal/inventory/{productId}/adjustments", productId)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(adjustment)
                .retrieve().bodyToMono(InventoryLevel.class).onErrorMap(this::mapUpstreamError);
    }

    static String ownerKey(Jwt jwt) {
        if (jwt == null || jwt.getIssuer() == null || jwt.getSubject() == null
                || jwt.getSubject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User authentication required");
        }
        // OAuth2 client-credentials tokens identify the application, not a
        // customer, so using them for carts would mix multiple customers.
        String clientId = jwt.getClaimAsString("client_id");
        if (jwt.getSubject().equals(clientId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A signed-in user token is required for cart operations");
        }
        String source = jwt.getIssuer().toString() + "\u0000" + jwt.getSubject();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private Throwable mapUpstreamError(Throwable error) {
        if (error instanceof WebClientResponseException response) {
            HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
            if (status != null && (status.is4xxClientError() || status.is5xxServerError())) {
                return new ResponseStatusException(status, "Downstream operation rejected");
            }
        }
        return error;
    }
}
