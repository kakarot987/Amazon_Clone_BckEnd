package amazon.microservices.core.product.services;

import amazon.api.commerce.WishlistItem;
import amazon.api.commerce.WishlistPage;
import amazon.microservices.core.product.persistence.ProductEntity;
import amazon.microservices.core.product.persistence.ProductRepository;
import amazon.microservices.core.product.persistence.WishlistEntity;
import amazon.microservices.core.product.persistence.WishlistEntry;
import amazon.microservices.core.product.persistence.WishlistRepository;
import amazon.util.exceptions.InvalidInputException;
import amazon.util.exceptions.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Owner-bound internal wishlist. The JWT-authenticated composite service
 * derives the opaque owner key; the product service must not be public.
 * Wishlist references never reserve stock or lock prices.
 */
@RestController
@RequestMapping("/internal/wishlists")
public class WishlistController {
    private static final int MAX_ITEMS = 100;
    private final WishlistRepository wishlists;
    private final ProductRepository products;

    public WishlistController(WishlistRepository wishlists, ProductRepository products) {
        this.wishlists = wishlists;
        this.products = products;
    }

    @GetMapping("/{ownerKey}")
    public Mono<WishlistPage> get(@PathVariable String ownerKey,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        validateOwner(ownerKey);
        validatePage(page, size);
        return wishlists.findById(ownerKey)
                .defaultIfEmpty(new WishlistEntity(ownerKey))
                .flatMap(wishlist -> toPage(wishlist, page, size));
    }

    @PutMapping("/{ownerKey}/items/{productId}")
    public Mono<WishlistPage> save(@PathVariable String ownerKey, @PathVariable int productId) {
        validateOwner(ownerKey);
        validateProduct(productId);
        // Deleted/missing products cannot be added; out-of-stock products can.
        return products.findByProductId(productId)
                .switchIfEmpty(Mono.error(new NotFoundException(
                        "No product found for productId: " + productId)))
                .then(mutate(ownerKey, list -> {
                    List<WishlistEntry> entries = new ArrayList<>(list.getEntries() == null
                            ? List.of() : list.getEntries());
                    if (entries.stream().anyMatch(e -> e.productId() == productId)) return list;
                    if (entries.size() >= MAX_ITEMS)
                        throw new InvalidInputException("Wishlist supports at most 100 products");
                    entries.add(new WishlistEntry(productId, Instant.now()));
                    list.setEntries(entries);
                    list.setUpdatedAt(Instant.now());
                    return list;
                }));
    }

    @DeleteMapping("/{ownerKey}/items/{productId}")
    public Mono<WishlistPage> remove(@PathVariable String ownerKey, @PathVariable int productId) {
        validateOwner(ownerKey);
        validateProduct(productId);
        return mutateExisting(ownerKey, list -> {
            List<WishlistEntry> entries = new ArrayList<>(list.getEntries() == null
                    ? List.of() : list.getEntries());
            if (entries.removeIf(e -> e.productId() == productId)) {
                list.setEntries(entries);
                list.setUpdatedAt(Instant.now());
            }
            return list;
        });
    }

    @DeleteMapping("/{ownerKey}")
    public Mono<WishlistPage> clear(@PathVariable String ownerKey) {
        validateOwner(ownerKey);
        return mutateExisting(ownerKey, list -> {
            if (list.getEntries() != null && !list.getEntries().isEmpty()) {
                list.setEntries(new ArrayList<>());
                list.setUpdatedAt(Instant.now());
            }
            return list;
        });
    }

    private Mono<WishlistPage> mutate(String ownerKey,
                                       Function<WishlistEntity, WishlistEntity> apply) {
        return Mono.defer(() -> wishlists.findById(ownerKey)
                        .defaultIfEmpty(new WishlistEntity(ownerKey))
                        .flatMap(current -> saveIfChanged(current, apply))
                        .flatMap(current -> toPage(current, 0, 20)))
                .retryWhen(concurrencyRetry());
    }

    private Mono<WishlistPage> mutateExisting(String ownerKey,
                                               Function<WishlistEntity, WishlistEntity> apply) {
        return Mono.defer(() -> wishlists.findById(ownerKey)
                        .flatMap(current -> saveIfChanged(current, apply))
                        .defaultIfEmpty(new WishlistEntity(ownerKey))
                        .flatMap(current -> toPage(current, 0, 20)))
                .retryWhen(concurrencyRetry());
    }

    private Mono<WishlistEntity> saveIfChanged(WishlistEntity existing,
                                                Function<WishlistEntity, WishlistEntity> apply) {
        Instant before = existing.getUpdatedAt();
        WishlistEntity changed = apply.apply(existing);
        // Repeated saves/removes are read-only, leaving the existing timestamp stable.
        if (java.util.Objects.equals(before, changed.getUpdatedAt()))
            return Mono.just(changed);
        return wishlists.save(changed);
    }

    private Retry concurrencyRetry() {
        return Retry.max(4)
                .filter(error -> error instanceof OptimisticLockingFailureException
                        || error instanceof DuplicateKeyException)
                .onRetryExhaustedThrow((spec, signal) ->
                        new InvalidInputException("Wishlist changed concurrently; retry request"));
    }

    private Mono<WishlistPage> toPage(WishlistEntity wishlist, int page, int size) {
        List<WishlistEntry> sorted = new ArrayList<>(wishlist.getEntries() == null
                ? List.of() : wishlist.getEntries());
        sorted.sort(Comparator.comparing(WishlistEntry::savedAt).reversed()
                .thenComparingInt(WishlistEntry::productId));
        int total = sorted.size();
        int start = (int) Math.min((long) page * size, total);
        List<WishlistEntry> entries = sorted.subList(start, Math.min(total, start + size));
        return Flux.fromIterable(entries)
                // Keep the user-selected order and avoid unbounded catalog fanout.
                .concatMap(entry -> products.findByProductId(entry.productId())
                        .map(product -> liveItem(entry, product))
                        .defaultIfEmpty(new WishlistItem(entry.productId(), entry.savedAt(),
                                false, false, null, null, null, null, null, null)))
                .collectList()
                .map(items -> new WishlistPage(items, page, size, total,
                        total == 0 ? 0 : (total + size - 1) / size, wishlist.getUpdatedAt()));
    }

    private WishlistItem liveItem(WishlistEntry entry, ProductEntity product) {
        List<String> urls = product.getImageUrls();
        return new WishlistItem(entry.productId(), entry.savedAt(), true,
                product.getStockQuantity() != null && product.getStockQuantity() > 0,
                product.getName(), product.getCategory(), product.getPrice(),
                product.getCurrency(), product.getStockQuantity(),
                urls == null || urls.isEmpty() ? null : urls.get(0));
    }

    private void validateOwner(String ownerKey) {
        if (ownerKey == null || !ownerKey.matches("[0-9a-f]{64}"))
            throw new InvalidInputException("Invalid wishlist identity");
    }

    private void validateProduct(int productId) {
        if (productId < 1) throw new InvalidInputException("Invalid productId");
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new InvalidInputException("page must be >= 0 and size must be 1-100");
    }
}
