package amazon.microservices.core.product.services;

import amazon.api.commerce.OrderHistoryItem;
import amazon.api.commerce.OrderHistoryPage;
import amazon.api.commerce.OrderReceipt;
import amazon.api.commerce.OrderReturnSummary;
import amazon.api.commerce.OrderShipmentSummary;
import amazon.api.commerce.OrderTimeline;
import amazon.api.commerce.OrderTimelineEvent;
import amazon.microservices.core.product.persistence.OrderEntity;
import amazon.microservices.core.product.persistence.OrderRepository;
import amazon.microservices.core.product.persistence.ReturnEntity;
import amazon.microservices.core.product.persistence.ReturnRepository;
import amazon.microservices.core.product.persistence.ShipmentEntity;
import amazon.microservices.core.product.persistence.ShipmentRepository;
import amazon.util.exceptions.InvalidInputException;
import amazon.util.exceptions.NotFoundException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Owner-scoped, bounded read-only order history. Shipment/return lookups are
 * limited to the requested page and always use the same owner-checked orders.
 */
@RestController
@RequestMapping("/internal/order-history")
public class OrderHistoryController {
    private static final int MAX_PAGE_SIZE = 50;
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            OrderEntity.PENDING, OrderEntity.RESERVED,
            OrderEntity.CANCELLATION_PENDING, OrderEntity.CANCELLED,
            OrderEntity.EXPIRATION_PENDING, OrderEntity.EXPIRED,
            OrderEntity.PAYMENT_PENDING, OrderEntity.PAYMENT_CONFIRMING,
            OrderEntity.MOCK_PAID, OrderEntity.PAYMENT_FAILED_PENDING,
            OrderEntity.PAYMENT_FAILED);
    private final OrderRepository orders;
    private final ShipmentRepository shipments;
    private final ReturnRepository returns;
    private final ReactiveMongoTemplate mongo;

    public OrderHistoryController(OrderRepository orders, ShipmentRepository shipments,
                                  ReturnRepository returns, ReactiveMongoTemplate mongo) {
        this.orders = orders;
        this.shipments = shipments;
        this.returns = returns;
        this.mongo = mongo;
    }

    @GetMapping("/{ownerKey}")
    public Mono<OrderHistoryPage> list(@PathVariable String ownerKey,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       @RequestParam(required = false) String status) {
        validateOwner(ownerKey);
        validatePaging(page, size);
        if (status != null && !ALLOWED_STATUSES.contains(status))
            throw new InvalidInputException("Unsupported order status");

        Criteria filter = Criteria.where("ownerKey").is(ownerKey);
        if (status != null) filter = filter.and("status").is(status);
        Query countQuery = Query.query(filter);
        Query pageQuery = Query.query(filter)
                .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                .skip((long) page * size)
                .limit(size);
        return Mono.zip(
                mongo.find(pageQuery, OrderEntity.class)
                        .concatMap(this::summary)
                        .collectList(),
                mongo.count(countQuery, OrderEntity.class))
                .map(result -> {
                    long total = result.getT2();
                    return new OrderHistoryPage(result.getT1(), page, size, total,
                            total == 0 ? 0 : 1 + (total - 1) / size);
                });
    }

    @GetMapping("/{ownerKey}/orders/{orderId}/timeline")
    public Mono<OrderTimeline> timeline(@PathVariable String ownerKey,
                                        @PathVariable String orderId) {
        validateOwner(ownerKey);
        validateId(orderId);
        return orders.findById(orderId)
                .filter(order -> ownerKey.equals(order.getOwnerKey()))
                .switchIfEmpty(Mono.error(new NotFoundException("Order not found")))
                .flatMap(order -> Mono.zip(
                        shipments.findById(orderId)
                                .map(java.util.Optional::of)
                                .defaultIfEmpty(java.util.Optional.empty()),
                        returns.findById(orderId)
                                .map(java.util.Optional::of)
                                .defaultIfEmpty(java.util.Optional.empty()))
                        .map(parts -> buildTimeline(order, parts.getT1().orElse(null),
                                parts.getT2().orElse(null))));
    }

    private Mono<OrderHistoryItem> summary(OrderEntity order) {
        String id = order.getId();
        return Mono.zip(
                shipments.findById(id).map(this::shipmentSummary)
                        .map(java.util.Optional::of)
                        .defaultIfEmpty(java.util.Optional.empty()),
                returns.findById(id).map(this::returnSummary)
                        .map(java.util.Optional::of)
                        .defaultIfEmpty(java.util.Optional.empty()))
                .map(parts -> new OrderHistoryItem(receipt(order),
                        parts.getT1().orElse(null), parts.getT2().orElse(null)));
    }

    private OrderTimeline buildTimeline(OrderEntity order, ShipmentEntity shipment,
                                        ReturnEntity returned) {
        List<OrderTimelineEvent> history = new ArrayList<>();
        if (order.getCreatedAt() != null)
            history.add(new OrderTimelineEvent("ORDER", "CREATED", order.getCreatedAt()));
        if (order.getPaymentStartedAt() != null)
            history.add(new OrderTimelineEvent("PAYMENT", "MOCK_PAYMENT_STARTED",
                    order.getPaymentStartedAt()));
        if (order.getMockPaidAt() != null)
            history.add(new OrderTimelineEvent("PAYMENT", "MOCK_PAID",
                    order.getMockPaidAt()));
        if (shipment != null && shipment.getHistory() != null)
            for (var event : shipment.getHistory()) {
                if (event.occurredAt() != null)
                    history.add(new OrderTimelineEvent("SHIPMENT", event.status(),
                            event.occurredAt()));
            }
        if (returned != null && returned.getHistory() != null)
            for (var event : returned.getHistory()) {
                if (event.occurredAt() != null)
                    history.add(new OrderTimelineEvent("RETURN", event.state(),
                            event.occurredAt()));
            }
        history.sort(Comparator.comparing(OrderTimelineEvent::occurredAt)
                .thenComparing(OrderTimelineEvent::source)
                .thenComparing(OrderTimelineEvent::status));
        return new OrderTimeline(receipt(order),
                shipment == null ? null : shipmentSummary(shipment),
                returned == null ? null : returnSummary(returned),
                List.copyOf(history));
    }

    private OrderShipmentSummary shipmentSummary(ShipmentEntity shipment) {
        return new OrderShipmentSummary(shipment.getStatus(), shipment.getTrackingNumber(),
                shipment.getUpdatedAt());
    }

    private OrderReturnSummary returnSummary(ReturnEntity returned) {
        return new OrderReturnSummary(returned.getState(), returned.getRefundAmount(),
                returned.getCurrency(), returned.getUpdatedAt());
    }

    private OrderReceipt receipt(OrderEntity order) {
        OrderReceipt view = new OrderReceipt(order.getId(), order.getStatus(),
                order.getProductId(), order.getQuantity(), order.getProductName(),
                order.getUnitPrice(), order.getCurrency(), order.getCreatedAt(),
                order.getExpiresAt());
        view.setPaymentStatus(order.getPaymentStatus());
        view.setMockPaidAt(order.getMockPaidAt());
        view.setItems(order.getItems());
        view.setTotal(order.getTotal());
        return view;
    }

    private void validateOwner(String ownerKey) {
        validateId(ownerKey);
    }

    private void validateId(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new InvalidInputException("Invalid order or owner identifier");
    }

    private void validatePaging(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE
                || (long) page * size > Integer.MAX_VALUE)
            throw new InvalidInputException("page must be nonnegative and size must be 1-50");
    }
}
