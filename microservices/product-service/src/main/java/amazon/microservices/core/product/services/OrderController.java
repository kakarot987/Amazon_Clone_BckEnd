package amazon.microservices.core.product.services;

import amazon.api.commerce.CartItem;
import amazon.api.commerce.OrderReceipt;
import amazon.microservices.core.product.persistence.CartRepository;
import amazon.microservices.core.product.persistence.OrderEntity;
import amazon.microservices.core.product.persistence.OrderRepository;
import amazon.microservices.core.product.persistence.ProductEntity;
import amazon.microservices.core.product.persistence.ProductRepository;
import amazon.util.exceptions.InvalidInputException;
import amazon.util.exceptions.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Single-product checkout prototype. Stock and the reservation ID are updated
 * atomically on one product document. The order intent is persisted separately
 * and can be resumed with the same idempotency key after a process crash.
 * No payment or shipping is initiated.
 */
@RestController
@RequestMapping("/internal/orders")
public class OrderController {
    private final OrderRepository orders;
    private final CartRepository carts;
    private final ProductRepository products;
    private final ReactiveMongoTemplate mongo;

    public OrderController(OrderRepository orders, CartRepository carts,
                           ProductRepository products, ReactiveMongoTemplate mongo) {
        this.orders = orders;
        this.carts = carts;
        this.products = products;
        this.mongo = mongo;
    }

    @PostMapping("/{ownerKey}")
    public Mono<OrderReceipt> checkout(@PathVariable String ownerKey,
                                       @RequestHeader("Idempotency-Key") String idempotencyKey) {
        validateOwner(ownerKey);
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._-]{8,100}"))
            throw new InvalidInputException("Idempotency-Key must contain 8-100 safe characters");
        String id = digest(ownerKey + "\u0000" + idempotencyKey);
        return orders.findById(id).flatMap(this::resume)
                .switchIfEmpty(Mono.defer(() -> createFromCart(ownerKey, id)));
    }

    @GetMapping("/{ownerKey}/{orderId}")
    public Mono<OrderReceipt> getOrder(@PathVariable String ownerKey, @PathVariable String orderId) {
        validateOwner(ownerKey);
        validateOrderId(orderId);
        return ownedOrder(ownerKey, orderId).map(this::receipt);
    }

    @PostMapping("/{ownerKey}/{orderId}/cancel")
    public Mono<OrderReceipt> cancel(@PathVariable String ownerKey, @PathVariable String orderId) {
        validateOwner(ownerKey);
        validateOrderId(orderId);
        return ownedOrder(ownerKey, orderId).flatMap(order -> {
            if (OrderEntity.CANCELLED.equals(order.getStatus())) return Mono.just(receipt(order));
            if (OrderEntity.CANCELLATION_PENDING.equals(order.getStatus()))
                return finishCancellation(order);
            if (!OrderEntity.RESERVED.equals(order.getStatus()))
                return Mono.error(new InvalidInputException("Only reserved orders may be cancelled"));
            order.setStatus(OrderEntity.CANCELLATION_PENDING);
            return orders.save(order)
                    .onErrorResume(OptimisticLockingFailureException.class,
                            ignored -> ownedOrder(ownerKey, orderId))
                    .flatMap(this::finishCancellation);
        });
    }

    private Mono<OrderEntity> ownedOrder(String ownerKey, String orderId) {
        return orders.findById(orderId)
                .filter(order -> ownerKey.equals(order.getOwnerKey()))
                .switchIfEmpty(Mono.error(new NotFoundException("Order not found")));
    }

    private Mono<OrderReceipt> createFromCart(String ownerKey, String id) {
        return carts.findById(ownerKey)
                .switchIfEmpty(Mono.error(new InvalidInputException("Cart is empty")))
                .flatMap(cart -> {
                    List<CartItem> items = cart.getItems();
                    if (items == null || items.size() != 1)
                        return Mono.error(new InvalidInputException(
                                "This checkout version supports exactly one cart item"));
                    CartItem item = items.get(0);
                    if (item.getProductId() < 1 || item.getQuantity() < 1 || item.getQuantity() > 99)
                        return Mono.error(new InvalidInputException("Invalid cart quantity"));
                    return products.findByProductId(item.getProductId())
                            .switchIfEmpty(Mono.error(new NotFoundException("Product not found")))
                            .flatMap(product -> {
                                BigDecimal price = product.getPrice();
                                if (price == null || price.signum() < 0 || price.scale() > 2
                                        || product.getCurrency() == null || product.getCurrency().isBlank())
                                    return Mono.error(new InvalidInputException(
                                            "Product price and currency must be configured"));
                                OrderEntity order = new OrderEntity(id, ownerKey, product.getProductId(),
                                        item.getQuantity(), product.getName(), price, product.getCurrency());
                                return orders.save(order)
                                        .onErrorResume(DuplicateKeyException.class,
                                                ignored -> orders.findById(id))
                                        .flatMap(this::resume);
                            });
                });
    }

    private Mono<OrderReceipt> resume(OrderEntity order) {
        if (!OrderEntity.PENDING.equals(order.getStatus())) return Mono.just(receipt(order));
        return reserve(order).then(Mono.defer(() -> {
            order.setStatus(OrderEntity.RESERVED);
            return orders.save(order)
                    .onErrorResume(OptimisticLockingFailureException.class,
                            ignored -> orders.findById(order.getId()));
        })).map(this::receipt);
    }

    private Mono<Void> reserve(OrderEntity order) {
        // The query and update run atomically on a single document. A retry
        // finds the reservation ID and does not decrement the stock again.
        Criteria criteria = Criteria.where("productId").is(order.getProductId())
                .and("stockQuantity").gte(order.getQuantity())
                .and("reservedOrderIds").ne(order.getId());
        Update update = new Update().inc("stockQuantity", -order.getQuantity())
                .addToSet("reservedOrderIds", order.getId());
        return mongo.findAndModify(Query.query(criteria), update,
                        FindAndModifyOptions.options().returnNew(true), ProductEntity.class)
                .hasElement().flatMap(written -> {
                    if (written) return Mono.<Void>empty();
                    return products.findByProductId(order.getProductId())
                            .switchIfEmpty(Mono.error(new NotFoundException("Product not found")))
                            .flatMap(product -> {
                                if (product.getReservedOrderIds() != null
                                        && product.getReservedOrderIds().contains(order.getId()))
                                    return Mono.<Void>empty();
                                return Mono.error(new InvalidInputException(
                                        "Insufficient or untracked inventory"));
                            });
                });
    }

    private Mono<OrderReceipt> finishCancellation(OrderEntity order) {
        if (OrderEntity.CANCELLED.equals(order.getStatus())) return Mono.just(receipt(order));
        if (!OrderEntity.CANCELLATION_PENDING.equals(order.getStatus()))
            return Mono.error(new InvalidInputException("Cancellation is unavailable"));

        Criteria criteria = Criteria.where("productId").is(order.getProductId())
                .and("reservedOrderIds").is(order.getId())
                .and("stockQuantity").lte(Integer.MAX_VALUE - order.getQuantity());
        Update release = new Update().inc("stockQuantity", order.getQuantity())
                .pull("reservedOrderIds", order.getId());

        return mongo.findAndModify(Query.query(criteria), release,
                        FindAndModifyOptions.options().returnNew(true), ProductEntity.class)
                .hasElement()
                .flatMap(released -> {
                    if (released) return Mono.just(true);
                    return products.findByProductId(order.getProductId())
                            .switchIfEmpty(Mono.error(new NotFoundException(
                                    "Product missing during cancellation")))
                            .flatMap(product -> {
                                if (product.getReservedOrderIds() != null
                                        && product.getReservedOrderIds().contains(order.getId()))
                                    return Mono.error(new InvalidInputException(
                                            "Cannot release reservation; check inventory"));
                                return Mono.just(false);
                            });
                })
                .then(orders.findById(order.getId()))
                .flatMap(current -> {
                    if (OrderEntity.CANCELLED.equals(current.getStatus()))
                        return Mono.just(receipt(current));
                    if (!OrderEntity.CANCELLATION_PENDING.equals(current.getStatus()))
                        return Mono.error(new InvalidInputException(
                                "Order status changed during cancellation"));
                    current.setStatus(OrderEntity.CANCELLED);
                    return orders.save(current)
                            .onErrorResume(OptimisticLockingFailureException.class,
                                    ignored -> orders.findById(order.getId()))
                            .map(this::receipt);
                });
    }

    private OrderReceipt receipt(OrderEntity order) {
        return new OrderReceipt(order.getId(), order.getStatus(), order.getProductId(),
                order.getQuantity(), order.getProductName(), order.getUnitPrice(),
                order.getCurrency(), order.getCreatedAt());
    }

    private void validateOwner(String ownerKey) {
        if (ownerKey == null || !ownerKey.matches("[0-9a-f]{64}"))
            throw new InvalidInputException("Invalid cart identity");
    }

    private void validateOrderId(String orderId) {
        if (orderId == null || !orderId.matches("[0-9a-f]{64}"))
            throw new InvalidInputException("Invalid order ID");
    }

    private static String digest(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
