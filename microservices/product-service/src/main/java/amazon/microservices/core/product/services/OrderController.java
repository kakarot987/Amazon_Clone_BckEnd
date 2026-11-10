package amazon.microservices.core.product.services;

import amazon.api.commerce.CartItem;
import amazon.api.commerce.OrderReceipt;
import amazon.api.commerce.OrderLine;
import amazon.api.commerce.PaymentIntent;
import amazon.api.commerce.MockPaymentEvent;
import amazon.microservices.core.product.persistence.CartRepository;
import amazon.microservices.core.product.persistence.OrderEntity;
import amazon.microservices.core.product.persistence.OrderRepository;
import amazon.microservices.core.product.persistence.ProductEntity;
import amazon.microservices.core.product.persistence.ProductRepository;
import amazon.util.exceptions.InvalidInputException;
import amazon.util.exceptions.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/**
 * Multi-item checkout prototype. Each product reservation is an atomic Mongo
 * document update, but the overall order is NOT a multi-document transaction.
 * PENDING orders are retried; partial failed reservations are compensated.
 * Payment and fulfillment remain simulated.
 */
@RestController
@RequestMapping("/internal/orders")
public class OrderController {
    private final OrderRepository orders;
    private final CartRepository carts;
    private final ProductRepository products;
    private final ReactiveMongoTemplate mongo;
    private final Duration reservationTtl;

    public OrderController(OrderRepository orders, CartRepository carts,
                           ProductRepository products, ReactiveMongoTemplate mongo,
                           @Value("${app.orders.reservation-ttl:PT15M}") Duration reservationTtl) {
        this.orders = orders;
        this.carts = carts;
        this.products = products;
        this.mongo = mongo;
        if (reservationTtl.isZero() || reservationTtl.isNegative())
            throw new IllegalArgumentException("Reservation TTL must be positive");
        this.reservationTtl = reservationTtl;
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
            if (OrderEntity.CANCELLED.equals(order.getStatus())
                    || OrderEntity.EXPIRED.equals(order.getStatus()))
                return Mono.just(receipt(order));
            if (OrderEntity.CANCELLATION_PENDING.equals(order.getStatus())
                    || OrderEntity.EXPIRATION_PENDING.equals(order.getStatus())
                    || OrderEntity.PAYMENT_FAILED_PENDING.equals(order.getStatus()))
                return finishCancellation(order);
            if (!OrderEntity.RESERVED.equals(order.getStatus())
                    && !OrderEntity.PAYMENT_PENDING.equals(order.getStatus()))
                return Mono.error(new InvalidInputException("Paid or non-reserved orders cannot be cancelled"));
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
                    if (items == null || items.isEmpty() || items.size() > 20)
                        return Mono.error(new InvalidInputException(
                                "Checkout supports 1-20 distinct products"));
                    Set<Integer> seen = new HashSet<>();
                    for (CartItem item : items) {
                        if (item == null || item.getProductId() < 1
                                || item.getQuantity() < 1 || item.getQuantity() > 99
                                || !seen.add(item.getProductId()))
                            return Mono.error(new InvalidInputException(
                                    "Cart contains an invalid or duplicate product"));
                    }
                    // Persist all authoritative product snapshots BEFORE
                    // deducting stock, so worker recovery has the full manifest.
                    List<CartItem> sorted = items.stream()
                            .sorted(Comparator.comparingInt(CartItem::getProductId))
                            .toList();
                    return Flux.fromIterable(sorted)
                            .concatMap(item -> products.findByProductId(item.getProductId())
                                    .switchIfEmpty(Mono.error(new NotFoundException(
                                            "Product not found: " + item.getProductId())))
                                    .map(product -> {
                                        BigDecimal price = product.getPrice();
                                        if (price == null || price.signum() < 0
                                                || price.scale() > 2
                                                || product.getCurrency() == null
                                                || product.getCurrency().isBlank()
                                                || product.getName() == null)
                                            throw new InvalidInputException(
                                                    "Product price, currency and name must be configured");
                                        return new ProductSnapshot(
                                                new OrderLine(product.getProductId(),
                                                        item.getQuantity(), product.getName(), price),
                                                product.getCurrency());
                                    }))
                            .collectList()
                            .flatMap(snapshots -> {
                                String currency = snapshots.get(0).currency();
                                if (snapshots.stream().anyMatch(s -> !currency.equals(s.currency())))
                                    return Mono.error(new InvalidInputException(
                                            "All checkout items must use the same currency"));
                                List<OrderLine> lines = snapshots.stream()
                                        .map(ProductSnapshot::line).toList();
                                OrderLine first = lines.get(0);
                                OrderEntity order = new OrderEntity(id, ownerKey, first.productId(),
                                        first.quantity(), first.productName(),
                                        first.unitPrice(), currency,
                                        Instant.now().plus(reservationTtl));
                                order.setItems(lines);
                                return orders.save(order)
                                        .onErrorResume(DuplicateKeyException.class,
                                                ignored -> orders.findById(id))
                                        .flatMap(this::resume);
                            });
                });
    }

    private record ProductSnapshot(OrderLine line, String currency) {}

    private Mono<OrderReceipt> resume(OrderEntity order) {
        if (!OrderEntity.PENDING.equals(order.getStatus()))
            return Mono.just(receipt(order));
        if (order.getExpiresAt() != null && !order.getExpiresAt().isAfter(Instant.now()))
            return failPendingReservation(order, new InvalidInputException("Order reservation has expired"));
        return reserve(order)
                .then(Mono.defer(() -> orders.findById(order.getId())
                        .flatMap(current -> {
                            if (!OrderEntity.PENDING.equals(current.getStatus()))
                                return Mono.just(current);
                            current.setStatus(OrderEntity.RESERVED);
                            return orders.save(current)
                                    .onErrorResume(OptimisticLockingFailureException.class,
                                            ex -> orders.findById(order.getId()));
                        })))
                .map(this::receipt)
                .onErrorResume(error -> failPendingReservation(order, error));
    }

    /** Compensation reuses the existing idempotent reservation-token release path. */
    private Mono<OrderReceipt> failPendingReservation(OrderEntity original, Throwable problem) {
        return orders.findById(original.getId())
                .flatMap(current -> {
                    if (!OrderEntity.PENDING.equals(current.getStatus()))
                        return Mono.just(receipt(current));
                    current.setStatus(OrderEntity.CANCELLATION_PENDING);
                    return orders.save(current)
                            .onErrorResume(OptimisticLockingFailureException.class,
                                    ex -> orders.findById(original.getId()))
                            .flatMap(again -> {
                                if (OrderEntity.CANCELLATION_PENDING.equals(again.getStatus()))
                                    return finishCancellation(again);
                                return Mono.just(receipt(again));
                            });
                })
                // Preserve both the user-facing failure and any needed
                // reconciliation state if cleanup has been interrupted.
                .then(Mono.error(problem));
    }

    private Mono<Void> reserve(OrderEntity order) {
        // Each product write is atomic, and each reservation is keyed by
        // the order ID. Replaying after a crash never deducts it twice.
        return Flux.fromIterable(order.getItems())
                .concatMap(line -> reserveLine(order.getId(), line))
                .then();
    }

    private Mono<Void> reserveLine(String orderId, OrderLine line) {
        Criteria criteria = Criteria.where("productId").is(line.productId())
                .and("stockQuantity").gte(line.quantity())
                .and("reservedOrderIds").ne(orderId);
        Update update = new Update().inc("stockQuantity", -line.quantity())
                .addToSet("reservedOrderIds", orderId);
        return mongo.findAndModify(Query.query(criteria), update,
                        FindAndModifyOptions.options().returnNew(true), ProductEntity.class)
                .hasElement().flatMap(written -> {
                    if (written) return Mono.<Void>empty();
                    return products.findByProductId(line.productId())
                            .switchIfEmpty(Mono.error(new NotFoundException("Product not found")))
                            .flatMap(product -> {
                                if (product.getReservedOrderIds() != null
                                        && product.getReservedOrderIds().contains(orderId))
                                    return Mono.<Void>empty();
                                return Mono.error(new InvalidInputException(
                                        "Insufficient or untracked inventory for product " + line.productId()));
                            });
                });
    }

    /** One mock-payment intent per reserved order, not a real payment charge. */
    @PostMapping("/{ownerKey}/{orderId}/payment")
    public Mono<PaymentIntent> startMockPayment(@PathVariable String ownerKey,
                                                @PathVariable String orderId) {
        validateOwner(ownerKey);
        validateOrderId(orderId);
        return ownedOrder(ownerKey, orderId).flatMap(this::startPayment);
    }

    private Mono<PaymentIntent> startPayment(OrderEntity order) {
        if (OrderEntity.PAYMENT_PENDING.equals(order.getStatus())
                || OrderEntity.PAYMENT_CONFIRMING.equals(order.getStatus())
                || OrderEntity.MOCK_PAID.equals(order.getStatus()))
            return Mono.just(toPaymentIntent(order));
        if (!OrderEntity.RESERVED.equals(order.getStatus()))
            return Mono.error(new InvalidInputException("Only an active reserved order can start mock payment"));
        if (order.getExpiresAt() == null || !order.getExpiresAt().isAfter(Instant.now()))
            return Mono.error(new InvalidInputException("Order reservation has expired"));
        order.setStatus(OrderEntity.PAYMENT_PENDING);
        order.setPaymentStatus("PENDING");
        order.setPaymentStartedAt(Instant.now());
        return orders.save(order)
                .onErrorResume(OptimisticLockingFailureException.class,
                        error -> orders.findById(order.getId()))
                .flatMap(current -> {
                    if (OrderEntity.PAYMENT_PENDING.equals(current.getStatus())
                            || OrderEntity.PAYMENT_CONFIRMING.equals(current.getStatus())
                            || OrderEntity.MOCK_PAID.equals(current.getStatus()))
                        return Mono.just(toPaymentIntent(current));
                    return Mono.error(new InvalidInputException("Order state changed during payment initiation"));
                });
    }

    private PaymentIntent toPaymentIntent(OrderEntity order) {
        return new PaymentIntent(order.getId(), order.getPaymentStatus(),
                order.getTotal(),
                order.getCurrency(), order.getPaymentStartedAt(), order.getExpiresAt());
    }

    /**
     * Called only by the internal signed-webhook handler. The state+event ID
     * update is atomic on the order document; duplicate webhooks are no-ops.
     */
    Mono<OrderReceipt> applyMockPaymentEvent(MockPaymentEvent event) {
        if (event == null || event.getEventId() == null
                || !event.getEventId().matches("[A-Za-z0-9._-]{8,100}")
                || event.getPaymentId() == null
                || !event.getPaymentId().matches("[0-9a-f]{64}")
                || (!"SUCCEEDED".equals(event.getOutcome())
                    && !"DECLINED".equals(event.getOutcome())))
            return Mono.error(new InvalidInputException("Invalid mock payment event"));

        return orders.findById(event.getPaymentId())
                .switchIfEmpty(Mono.error(new NotFoundException("Payment not found")))
                .flatMap(order -> applyEventToOrder(order, event));
    }

    private Mono<OrderReceipt> applyEventToOrder(OrderEntity order, MockPaymentEvent event) {
        if (event.getEventId().equals(order.getPaymentEventId())) {
            if (("SUCCEEDED".equals(event.getOutcome())
                    && (OrderEntity.PAYMENT_CONFIRMING.equals(order.getStatus())
                        || OrderEntity.MOCK_PAID.equals(order.getStatus())))
                    || ("DECLINED".equals(event.getOutcome())
                        && (OrderEntity.PAYMENT_FAILED_PENDING.equals(order.getStatus())
                            || OrderEntity.PAYMENT_FAILED.equals(order.getStatus())))) {
                return settlePayment(order);
            }
            return Mono.error(new InvalidInputException("Conflicting replay for payment event"));
        }
        if (!OrderEntity.PAYMENT_PENDING.equals(order.getStatus()))
            return Mono.error(new InvalidInputException("Payment cannot change a terminal order"));
        if (order.getExpiresAt() == null || !order.getExpiresAt().isAfter(Instant.now()))
            return Mono.error(new InvalidInputException("Payment window expired"));
        order.setPaymentEventId(event.getEventId());
        if ("SUCCEEDED".equals(event.getOutcome())) {
            order.setStatus(OrderEntity.PAYMENT_CONFIRMING);
            order.setPaymentStatus("SUCCEEDED");
        } else {
            order.setStatus(OrderEntity.PAYMENT_FAILED_PENDING);
            order.setPaymentStatus("DECLINED");
        }
        return orders.save(order)
                .onErrorResume(OptimisticLockingFailureException.class,
                        error -> orders.findById(order.getId()))
                .flatMap(current -> {
                    if (!event.getEventId().equals(current.getPaymentEventId())
                            || ("SUCCEEDED".equals(event.getOutcome())
                                && !("SUCCEEDED".equals(current.getPaymentStatus())))
                            || ("DECLINED".equals(event.getOutcome())
                                && !("DECLINED".equals(current.getPaymentStatus()))))
                        return Mono.error(new InvalidInputException("Conflicting payment callback"));
                    return settlePayment(current);
                });
    }

    private Mono<OrderReceipt> settlePayment(OrderEntity order) {
        if (OrderEntity.PAYMENT_CONFIRMING.equals(order.getStatus()))
            return finishPaymentConfirmation(order);
        if (OrderEntity.PAYMENT_FAILED_PENDING.equals(order.getStatus()))
            return finishCancellation(order);
        return Mono.just(receipt(order));
    }

    /**
     * Sale finalization removes the held reservation token WITHOUT restoring
     * available stock: stock was already decremented when reserved.
     */
    private Mono<OrderReceipt> finishPaymentConfirmation(OrderEntity order) {
        if (!OrderEntity.PAYMENT_CONFIRMING.equals(order.getStatus()))
            return Mono.just(receipt(order));
        // Remove every product's hold token WITHOUT increasing its stock.
        // Each individual update can be safely replayed after a crash.
        return Flux.fromIterable(order.getItems())
                .concatMap(line -> finalizeLine(order.getId(), line))
                .then(orders.findById(order.getId()))
                .flatMap(current -> {
                    if (OrderEntity.MOCK_PAID.equals(current.getStatus()))
                        return Mono.just(receipt(current));
                    if (!OrderEntity.PAYMENT_CONFIRMING.equals(current.getStatus()))
                        return Mono.error(new InvalidInputException(
                                "Order changed during payment confirmation"));
                    current.setStatus(OrderEntity.MOCK_PAID);
                    current.setMockPaidAt(Instant.now());
                    return orders.save(current)
                            .onErrorResume(OptimisticLockingFailureException.class,
                                    ex -> orders.findById(order.getId()))
                            .map(this::receipt);
                });
    }

    private Mono<Void> finalizeLine(String orderId, OrderLine line) {
        Criteria filter = Criteria.where("productId").is(line.productId())
                .and("reservedOrderIds").is(orderId);
        return mongo.findAndModify(Query.query(filter),
                        new Update().pull("reservedOrderIds", orderId),
                        FindAndModifyOptions.options().returnNew(true), ProductEntity.class)
                .hasElement()
                .flatMap(removed -> {
                    if (removed) return Mono.<Void>empty();
                    return products.findByProductId(line.productId())
                            .switchIfEmpty(Mono.error(new NotFoundException(
                                    "Product missing during payment confirmation: " + line.productId())))
                            .flatMap(product -> {
                                if (product.getReservedOrderIds() != null
                                        && product.getReservedOrderIds().contains(orderId))
                                    return Mono.error(new InvalidInputException(
                                            "Cannot finalize mock payment item " + line.productId()));
                                return Mono.<Void>empty();
                            });
                });
    }

    private Mono<OrderReceipt> finishCancellation(OrderEntity order) {
        if (OrderEntity.CANCELLED.equals(order.getStatus())
                || OrderEntity.EXPIRED.equals(order.getStatus()))
            return Mono.just(receipt(order));
        if (!OrderEntity.CANCELLATION_PENDING.equals(order.getStatus())
                && !OrderEntity.EXPIRATION_PENDING.equals(order.getStatus())
                && !OrderEntity.PAYMENT_FAILED_PENDING.equals(order.getStatus()))
            return Mono.error(new InvalidInputException("Cancellation is unavailable"));

        // When an earlier step failed, some line tokens may be absent. Only
        // products that still contain the reservation ID are incremented.
        return Flux.fromIterable(order.getItems())
                .concatMap(line -> releaseLine(order.getId(), line))
                .then(orders.findById(order.getId()))
                .flatMap(current -> {
                    if (OrderEntity.CANCELLED.equals(current.getStatus())
                            || OrderEntity.EXPIRED.equals(current.getStatus())
                            || OrderEntity.PAYMENT_FAILED.equals(current.getStatus()))
                        return Mono.just(receipt(current));
                    if (!OrderEntity.CANCELLATION_PENDING.equals(current.getStatus())
                            && !OrderEntity.EXPIRATION_PENDING.equals(current.getStatus())
                            && !OrderEntity.PAYMENT_FAILED_PENDING.equals(current.getStatus()))
                        return Mono.error(new InvalidInputException(
                                "Order status changed during cancellation"));
                    current.setStatus(OrderEntity.EXPIRATION_PENDING.equals(current.getStatus())
                            ? OrderEntity.EXPIRED
                            : OrderEntity.PAYMENT_FAILED_PENDING.equals(current.getStatus())
                              ? OrderEntity.PAYMENT_FAILED : OrderEntity.CANCELLED);
                    if (OrderEntity.PAYMENT_FAILED.equals(current.getStatus()))
                        current.setPaymentStatus("DECLINED");
                    return orders.save(current)
                            .onErrorResume(OptimisticLockingFailureException.class,
                                    ex -> orders.findById(order.getId()))
                            .map(this::receipt);
                });
    }

    private Mono<Void> releaseLine(String orderId, OrderLine line) {
        Criteria criteria = Criteria.where("productId").is(line.productId())
                .and("reservedOrderIds").is(orderId)
                .and("stockQuantity").lte(Integer.MAX_VALUE - line.quantity());
        Update release = new Update().inc("stockQuantity", line.quantity())
                .pull("reservedOrderIds", orderId);
        return mongo.findAndModify(Query.query(criteria), release,
                        FindAndModifyOptions.options().returnNew(true), ProductEntity.class)
                .hasElement()
                .flatMap(released -> {
                    if (released) return Mono.<Void>empty();
                    return products.findByProductId(line.productId())
                            .switchIfEmpty(Mono.error(new NotFoundException(
                                    "Product missing during stock release: " + line.productId())))
                            .flatMap(product -> {
                                if (product.getReservedOrderIds() != null
                                        && product.getReservedOrderIds().contains(orderId))
                                    return Mono.error(new InvalidInputException(
                                            "Stock release failed; inspect product " + line.productId()));
                                return Mono.<Void>empty();
                            });
                });
    }


    /**
     * Internal recovery entry point. Always reloads current state to avoid
     * acting on stale status snapshots from a background scan.
     */
    Mono<OrderReceipt> reconcileOrder(String id, Instant now) {
        return orders.findById(id).flatMap(order -> {
            if (OrderEntity.PENDING.equals(order.getStatus()))
                return resume(order);
            if (OrderEntity.CANCELLATION_PENDING.equals(order.getStatus())
                    || OrderEntity.EXPIRATION_PENDING.equals(order.getStatus())
                    || OrderEntity.PAYMENT_FAILED_PENDING.equals(order.getStatus()))
                return finishCancellation(order);
            if (OrderEntity.PAYMENT_CONFIRMING.equals(order.getStatus()))
                return finishPaymentConfirmation(order);
            if ((OrderEntity.RESERVED.equals(order.getStatus())
                    || OrderEntity.PAYMENT_PENDING.equals(order.getStatus()))
                    && order.getExpiresAt() != null && !order.getExpiresAt().isAfter(now))
                return expireReservation(order);
            return Mono.just(receipt(order));
        });
    }

    private Mono<OrderReceipt> expireReservation(OrderEntity order) {
        if (!OrderEntity.RESERVED.equals(order.getStatus())
                && !OrderEntity.PAYMENT_PENDING.equals(order.getStatus()))
            return Mono.just(receipt(order));
        order.setStatus(OrderEntity.EXPIRATION_PENDING);
        return orders.save(order)
                .onErrorResume(OptimisticLockingFailureException.class,
                        ignored -> orders.findById(order.getId()))
                .flatMap(current -> {
                    if (OrderEntity.EXPIRATION_PENDING.equals(current.getStatus())
                            || OrderEntity.CANCELLATION_PENDING.equals(current.getStatus()))
                        return finishCancellation(current);
                    return Mono.just(receipt(current));
                });
    }

    private OrderReceipt receipt(OrderEntity order) {
        OrderReceipt response = new OrderReceipt(order.getId(), order.getStatus(), order.getProductId(),
                order.getQuantity(), order.getProductName(), order.getUnitPrice(),
                order.getCurrency(), order.getCreatedAt(), order.getExpiresAt());
        response.setPaymentStatus(order.getPaymentStatus());
        response.setMockPaidAt(order.getMockPaidAt());
        response.setItems(order.getItems());
        response.setTotal(order.getTotal());
        return response;
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
