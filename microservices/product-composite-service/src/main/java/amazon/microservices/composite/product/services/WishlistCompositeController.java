package amazon.microservices.composite.product.services;

import amazon.api.commerce.WishlistPage;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** User-owned saved items. A client cannot choose a different user identity. */
@RestController
@RequestMapping("/product-composite/wishlist")
public class WishlistCompositeController {
    private final WebClient client;

    public WishlistCompositeController(WebClient client) {
        this.client = client;
    }

    @GetMapping(produces = "application/json")
    public Mono<WishlistPage> get(@AuthenticationPrincipal Jwt jwt,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        String owner = CommerceCompositeController.ownerKey(jwt);
        return client.get().uri(builder -> builder.scheme("http").host("product")
                .path("/internal/wishlists/{owner}")
                .queryParam("page", page).queryParam("size", size).build(owner))
                .retrieve().bodyToMono(WishlistPage.class).onErrorMap(this::mapError);
    }

    @PutMapping(value = "/items/{productId}", produces = "application/json")
    public Mono<WishlistPage> add(@AuthenticationPrincipal Jwt jwt, @PathVariable int productId) {
        String owner = CommerceCompositeController.ownerKey(jwt);
        return client.put().uri("http://product/internal/wishlists/{owner}/items/{id}",
                        owner, productId)
                .retrieve().bodyToMono(WishlistPage.class).onErrorMap(this::mapError);
    }

    @DeleteMapping(value = "/items/{productId}", produces = "application/json")
    public Mono<WishlistPage> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable int productId) {
        String owner = CommerceCompositeController.ownerKey(jwt);
        return client.delete().uri("http://product/internal/wishlists/{owner}/items/{id}",
                        owner, productId)
                .retrieve().bodyToMono(WishlistPage.class).onErrorMap(this::mapError);
    }

    @DeleteMapping(produces = "application/json")
    public Mono<WishlistPage> clear(@AuthenticationPrincipal Jwt jwt) {
        String owner = CommerceCompositeController.ownerKey(jwt);
        return client.delete().uri("http://product/internal/wishlists/{owner}", owner)
                .retrieve().bodyToMono(WishlistPage.class).onErrorMap(this::mapError);
    }

    private Throwable mapError(Throwable error) {
        if (error instanceof WebClientResponseException response) {
            HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
            if (status != null)
                return new ResponseStatusException(status, "Wishlist operation rejected");
        }
        return error;
    }
}
