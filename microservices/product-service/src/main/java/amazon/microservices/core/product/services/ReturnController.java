package amazon.microservices.core.product.services;

import amazon.api.commerce.ReturnAction;
import amazon.api.commerce.ReturnEvent;
import amazon.api.commerce.ReturnReceipt;
import amazon.api.commerce.ReturnRequest;
import amazon.microservices.core.product.persistence.OrderEntity;
import amazon.microservices.core.product.persistence.OrderRepository;
import amazon.microservices.core.product.persistence.ReturnEntity;
import amazon.microservices.core.product.persistence.ReturnRepository;
import amazon.microservices.core.product.persistence.ShipmentEntity;
import amazon.microservices.core.product.persistence.ShipmentRepository;
import amazon.util.exceptions.InvalidInputException;
import amazon.util.exceptions.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Simulated single-order return workflow; no money or stock movement.
 * Only the JWT-authenticated composite service is publicly accessible.
 * Repository saves use @Version and retry to prevent concurrent state races.
 */
@RestController
@RequestMapping("/internal/returns")
public class ReturnController {
    private static final Set<String> REASONS = Set.of(
            "DAMAGED", "WRONG_ITEM", "NOT_AS_DESCRIBED", "NO_LONGER_NEEDED");
    private final ReturnRepository returns;
    private final OrderRepository orders;
    private final ShipmentRepository shipments;
    private final Duration returnWindow;

    public ReturnController(ReturnRepository returns, OrderRepository orders,
                            ShipmentRepository shipments,
                            @Value("${app.returns.window:P30D}") Duration returnWindow) {
        if (returnWindow.isNegative() || returnWindow.isZero())
            throw new IllegalArgumentException("Return window must be positive");
        this.returns = returns;
        this.orders = orders;
        this.shipments = shipments;
        this.returnWindow = returnWindow;
    }

    /** Request a return; the order ID can appear only once in the collection. */
    @PostMapping("/owner/{ownerKey}/order/{orderId}")
    public Mono<ReturnReceipt> request(@PathVariable String ownerKey,
                                       @PathVariable String orderId,
                                       @RequestBody ReturnRequest request) {
        validateId(ownerKey);
        validateId(orderId);
        if (request == null || request.reasonCode() == null
                || !REASONS.contains(request.reasonCode()))
            throw new InvalidInputException("Use a supported reasonCode");

        return returns.findById(orderId)
                .flatMap(existing -> {
                    if (!ownerKey.equals(existing.getOwnerKey()))
                        return Mono.error(new NotFoundException("Order not found"));
                    if (!request.reasonCode().equals(existing.getReasonCode()))
                        return Mono.error(new InvalidInputException(
                                "A return request already exists with another reason"));
                    return Mono.just(receipt(existing));
                })
                .switchIfEmpty(Mono.defer(() -> createForDeliveredOrder(
                        ownerKey, orderId, request.reasonCode())));
    }

    private Mono<ReturnReceipt> createForDeliveredOrder(String ownerKey, String orderId,
                                                         String reasonCode) {
        return orders.findById(orderId)
                .filter(order -> ownerKey.equals(order.getOwnerKey()))
                .switchIfEmpty(Mono.error(new NotFoundException("Order not found")))
                .flatMap(order -> {
                    if (!OrderEntity.MOCK_PAID.equals(order.getStatus()))
                        return Mono.error(new InvalidInputException(
                                "Only mock-paid orders can be returned"));
                    return shipments.findById(orderId)
                            .switchIfEmpty(Mono.error(new InvalidInputException(
                                    "Order is not delivered")))
                            .flatMap(shipment -> {
                                if (!"DELIVERED".equals(shipment.getStatus()))
                                    return Mono.error(new InvalidInputException(
                                            "Shipment must be delivered before requesting a return"));
                                Instant deliveredAt = deliveredAt(shipment);
                                if (deliveredAt == null || deliveredAt.plus(returnWindow).isBefore(Instant.now()))
                                    return Mono.error(new InvalidInputException(
                                            "Return request window has closed"));
                                BigDecimal amount = order.getTotal();
                                return returns.save(new ReturnEntity(
                                                orderId, ownerKey, reasonCode, amount,
                                                order.getCurrency()))
                                        .onErrorResume(DuplicateKeyException.class,
                                                ex -> returns.findById(orderId))
                                        .flatMap(existing -> {
                                            if (!ownerKey.equals(existing.getOwnerKey()))
                                                return Mono.error(new NotFoundException("Order not found"));
                                            if (!reasonCode.equals(existing.getReasonCode()))
                                                return Mono.error(new InvalidInputException(
                                                        "A different return request already exists"));
                                            return Mono.just(receipt(existing));
                                        });
                            });
                });
    }

    /** Only the JWT owner's return may be accessed from the customer route. */
    @GetMapping("/owner/{ownerKey}/order/{orderId}")
    public Mono<ReturnReceipt> getForCustomer(@PathVariable String ownerKey,
                                               @PathVariable String orderId) {
        validateId(ownerKey);
        validateId(orderId);
        return returns.findById(orderId)
                .filter(r -> ownerKey.equals(r.getOwnerKey()))
                .switchIfEmpty(Mono.error(new NotFoundException("Return not found")))
                .map(this::receipt);
    }

    /** Dedicated returns-operator read. */
    @GetMapping("/{orderId}")
    public Mono<ReturnReceipt> getForOperator(@PathVariable String orderId) {
        validateId(orderId);
        return findReturn(orderId).map(this::receipt);
    }

    @PostMapping("/{orderId}/approve")
    public Mono<ReturnReceipt> approve(@PathVariable String orderId, @RequestBody ReturnAction action) {
        return transition(orderId, action, "REQUESTED", "APPROVED");
    }

    @PostMapping("/{orderId}/reject")
    public Mono<ReturnReceipt> reject(@PathVariable String orderId, @RequestBody ReturnAction action) {
        return transition(orderId, action, "REQUESTED", "REJECTED");
    }

    @PostMapping("/{orderId}/receive")
    public Mono<ReturnReceipt> receive(@PathVariable String orderId, @RequestBody ReturnAction action) {
        return transition(orderId, action, "APPROVED", "RECEIVED");
    }

    /** MOCK_REFUNDED marks a test ledger entry only; no provider call. */
    @PostMapping("/{orderId}/mock-refund")
    public Mono<ReturnReceipt> mockRefund(@PathVariable String orderId, @RequestBody ReturnAction action) {
        return transition(orderId, action, "RECEIVED", "MOCK_REFUNDED");
    }

    private Mono<ReturnReceipt> transition(String orderId, ReturnAction action,
                                           String from, String to) {
        validateId(orderId);
        if (action == null || action.eventId() == null
                || !action.eventId().matches("[A-Za-z0-9._-]{8,100}"))
            throw new InvalidInputException("eventId must be 8-100 safe characters");

        return Mono.defer(() -> findReturn(orderId).flatMap(entity -> {
                    List<ReturnEvent> history = entity.getHistory() == null
                            ? List.of() : entity.getHistory();
                    for (ReturnEvent event : history) {
                        if (event.eventId().equals(action.eventId())) {
                            if (to.equals(event.state())) return Mono.just(receipt(entity));
                            return Mono.error(new InvalidInputException(
                                    "eventId was already used for a different return transition"));
                        }
                    }
                    if (!from.equals(entity.getState()))
                        return Mono.error(new InvalidInputException(
                                "Return transition is not allowed from " + entity.getState()));
                    Instant now = Instant.now();
                    List<ReturnEvent> nextHistory = new ArrayList<>(history);
                    nextHistory.add(new ReturnEvent(action.eventId(), to, now));
                    entity.setState(to);
                    entity.setHistory(nextHistory);
                    entity.setUpdatedAt(now);
                    return returns.save(entity).map(this::receipt);
                }))
                .retryWhen(Retry.max(4)
                        .filter(ex -> ex instanceof OptimisticLockingFailureException)
                        .onRetryExhaustedThrow((spec, signal) ->
                                new InvalidInputException("Concurrent return update; retry request")));
    }

    private Mono<ReturnEntity> findReturn(String orderId) {
        return returns.findById(orderId)
                .switchIfEmpty(Mono.error(new NotFoundException("Return not found")));
    }

    private Instant deliveredAt(ShipmentEntity shipment) {
        if (shipment.getHistory() == null) return null;
        return shipment.getHistory().stream()
                .filter(event -> "DELIVERED".equals(event.status()))
                .map(event -> event.occurredAt()).findFirst().orElse(null);
    }

    private ReturnReceipt receipt(ReturnEntity entity) {
        return new ReturnReceipt(entity.getId(), entity.getState(), entity.getReasonCode(),
                entity.getRefundAmount(), entity.getCurrency(), entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getHistory() == null ? List.of() : List.copyOf(entity.getHistory()));
    }

    private void validateId(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new InvalidInputException("Invalid return or owner ID");
    }
}
