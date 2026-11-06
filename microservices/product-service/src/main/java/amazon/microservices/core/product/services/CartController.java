package amazon.microservices.core.product.services;

import amazon.api.commerce.CartItem;
import amazon.api.commerce.QuantityRequest;
import amazon.api.commerce.ShoppingCart;
import amazon.microservices.core.product.persistence.CartEntity;
import amazon.microservices.core.product.persistence.CartRepository;
import amazon.microservices.core.product.persistence.ProductRepository;
import amazon.util.exceptions.InvalidInputException;
import amazon.util.exceptions.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Internal cart API: composite service hashes the verified JWT identity.
 * This service must remain inaccessible from the public internet.
 */
@RestController
@RequestMapping("/internal/carts")
public class CartController {
    private static final int MAX_ITEM_QUANTITY = 99;
    private static final int MAX_DISTINCT_PRODUCTS = 50;

    private final CartRepository carts;
    private final ProductRepository products;

    public CartController(CartRepository carts, ProductRepository products) {
        this.carts = carts;
        this.products = products;
    }

    @GetMapping("/{ownerKey}")
    public Mono<ShoppingCart> getCart(@PathVariable String ownerKey) {
        validateOwnerKey(ownerKey);
        return carts.findById(ownerKey)
                .map(this::toResponse)
                .defaultIfEmpty(new ShoppingCart(List.of(), null));
    }

    @PutMapping("/{ownerKey}/items/{productId}")
    public Mono<ShoppingCart> setItem(@PathVariable String ownerKey, @PathVariable int productId,
                                      @RequestBody QuantityRequest body) {
        validateOwnerKey(ownerKey);
        if (productId < 1 || body == null || body.getQuantity() < 1
                || body.getQuantity() > MAX_ITEM_QUANTITY) {
            throw new InvalidInputException("productId must be positive and quantity must be 1-99");
        }

        // Stock is checked but NOT reserved. Checkout must revalidate inventory.
        return products.findByProductId(productId)
                .switchIfEmpty(Mono.error(new NotFoundException("No product found for productId: " + productId)))
                .flatMap(product -> {
                    if (product.getStockQuantity() == null
                            || product.getStockQuantity() < body.getQuantity()) {
                        return Mono.error(new InvalidInputException("Product has insufficient or untracked stock"));
                    }
                    return mutate(ownerKey, cart -> {
                        List<CartItem> items = new ArrayList<>(cart.getItems());
                        items.removeIf(item -> item.getProductId() == productId);
                        if (items.size() >= MAX_DISTINCT_PRODUCTS)
                            throw new InvalidInputException("Cart supports at most 50 distinct products");
                        items.add(new CartItem(productId, body.getQuantity(), product.getName(),
                                product.getPrice(), product.getCurrency()));
                        cart.setItems(items);
                        return cart;
                    });
                });
    }

    @DeleteMapping("/{ownerKey}/items/{productId}")
    public Mono<ShoppingCart> removeItem(@PathVariable String ownerKey, @PathVariable int productId) {
        validateOwnerKey(ownerKey);
        if (productId < 1) throw new InvalidInputException("productId must be positive");
        return Mono.defer(() -> carts.findById(ownerKey)
                    .flatMap(existing -> {
                        List<CartItem> items = new ArrayList<>(existing.getItems());
                        if (items.removeIf(item -> item.getProductId() == productId)) {
                            existing.setItems(items);
                            existing.setUpdatedAt(Instant.now());
                            return carts.save(existing).map(this::toResponse);
                        }
                        return Mono.just(toResponse(existing));
                    })
                    .defaultIfEmpty(new ShoppingCart(List.of(), null)))
                .retryWhen(retryConcurrentChanges());
    }

    private Mono<ShoppingCart> mutate(String ownerKey, Function<CartEntity, CartEntity> change) {
        return Mono.defer(() -> carts.findById(ownerKey)
                    .defaultIfEmpty(new CartEntity(ownerKey))
                    .map(change)
                    .flatMap(cart -> {
                        cart.setUpdatedAt(Instant.now());
                        return carts.save(cart);
                    })
                    .map(this::toResponse))
                .retryWhen(retryConcurrentChanges());
    }

    private Retry retryConcurrentChanges() {
        return Retry.max(4)
                .filter(error -> error instanceof OptimisticLockingFailureException
                        || error instanceof DuplicateKeyException)
                .onRetryExhaustedThrow((spec, signal) ->
                        new InvalidInputException("Cart changed concurrently; please retry"));
    }

    private ShoppingCart toResponse(CartEntity cart) {
        List<CartItem> items = new ArrayList<>(cart.getItems() == null ? List.of() : cart.getItems());
        items.sort(Comparator.comparingInt(CartItem::getProductId));
        return new ShoppingCart(items, cart.getUpdatedAt());
    }

    private void validateOwnerKey(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}")) {
            throw new InvalidInputException("Invalid cart identity");
        }
    }
}
