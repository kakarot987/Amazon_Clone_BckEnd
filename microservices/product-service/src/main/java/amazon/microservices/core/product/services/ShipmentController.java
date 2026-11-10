package amazon.microservices.core.product.services;

import amazon.api.commerce.CreateShipmentRequest;
import amazon.api.commerce.ShipmentDetails;
import amazon.api.commerce.ShipmentEvent;
import amazon.api.commerce.ShipmentStatusUpdate;
import amazon.microservices.core.product.persistence.OrderEntity;
import amazon.microservices.core.product.persistence.OrderRepository;
import amazon.microservices.core.product.persistence.ShipmentEntity;
import amazon.microservices.core.product.persistence.ShipmentRepository;
import amazon.util.exceptions.InvalidInputException;
import amazon.util.exceptions.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Internal mock fulfillment service. Only the JWT-authorized composite API
 * may expose it. No real carrier booking, labels, address or external APIs.
 *
 * Order state remains MOCK_PAID; shipment status tracks fulfillment separately.
 */
@RestController
@RequestMapping("/internal/shipments")
public class ShipmentController {
    private final ShipmentRepository shipments;
    private final OrderRepository orders;

    public ShipmentController(ShipmentRepository shipments, OrderRepository orders) {
        this.shipments = shipments;
        this.orders = orders;
    }

    /** Restricted to fulfillment operator scope at the composite gateway. */
    @PostMapping
    public Mono<ShipmentDetails> create(@RequestBody CreateShipmentRequest request) {
        if (request == null) throw new InvalidInputException("Shipment request is required");
        String orderId = request.orderId();
        validateId(orderId);
        String zone = request.destinationZone();
        if (zone == null || !zone.matches("[A-Za-z0-9][A-Za-z0-9_-]{1,31}"))
            throw new InvalidInputException("destinationZone must be a 2-32 character zone code");

        return orders.findById(orderId)
                .switchIfEmpty(Mono.error(new NotFoundException("Order not found")))
                .flatMap(order -> {
                    if (!OrderEntity.MOCK_PAID.equals(order.getStatus()))
                        return Mono.error(new InvalidInputException(
                                "Only mock-paid orders are eligible for fulfillment"));
                    return shipments.findById(orderId)
                            .switchIfEmpty(Mono.defer(() -> shipments.save(
                                    new ShipmentEntity(orderId, zone,
                                            "MOCK-" + orderId.substring(0, 20).toUpperCase(Locale.ROOT)))
                                    .onErrorResume(DuplicateKeyException.class,
                                            error -> shipments.findById(orderId))))
                            .flatMap(shipment -> {
                                if (!zone.equals(shipment.getDestinationZone()))
                                    return Mono.error(new InvalidInputException(
                                            "Existing shipment has a different destination zone"));
                                return Mono.just(details(shipment));
                            });
                });
    }

    /** Customer tracking is scoped to the owner of the original order. */
    @GetMapping("/owner/{ownerKey}/order/{orderId}")
    public Mono<ShipmentDetails> getForCustomer(@PathVariable String ownerKey,
                                                 @PathVariable String orderId) {
        validateOwner(ownerKey);
        validateId(orderId);
        return orders.findById(orderId)
                .filter(order -> ownerKey.equals(order.getOwnerKey()))
                .switchIfEmpty(Mono.error(new NotFoundException("Order not found")))
                .flatMap(order -> lookup(orderId));
    }

    /** Fulfillment-operator-only read. */
    @GetMapping("/{orderId}")
    public Mono<ShipmentDetails> getForOperator(@PathVariable String orderId) {
        validateId(orderId);
        return lookup(orderId);
    }

    /** Monotonic event transitions; retries of the same event are safe. */
    @PostMapping("/{orderId}/events")
    public Mono<ShipmentDetails> recordEvent(@PathVariable String orderId,
                                             @RequestBody ShipmentStatusUpdate update) {
        validateId(orderId);
        if (update == null || update.eventId() == null
                || !update.eventId().matches("[A-Za-z0-9._-]{8,100}"))
            throw new InvalidInputException("eventId must be 8-100 safe characters");
        if (!List.of("PACKED", "SHIPPED", "IN_TRANSIT", "DELIVERED")
                .contains(update.status()))
            throw new InvalidInputException("Invalid simulated shipment milestone");

        return Mono.defer(() -> shipments.findById(orderId)
                        .switchIfEmpty(Mono.error(new NotFoundException("Shipment not found")))
                        .flatMap(shipment -> applyEvent(shipment, update)))
                .retryWhen(Retry.max(3)
                        .filter(error -> error instanceof OptimisticLockingFailureException)
                        .onRetryExhaustedThrow((spec, signal) ->
                                new InvalidInputException("Concurrent shipment update; retry request")));
    }

    private Mono<ShipmentDetails> applyEvent(ShipmentEntity shipment, ShipmentStatusUpdate update) {
        List<ShipmentEvent> history = shipment.getHistory() == null
                ? List.of() : shipment.getHistory();
        for (ShipmentEvent event : history) {
            if (event.eventId().equals(update.eventId())) {
                if (event.status().equals(update.status())) return Mono.just(details(shipment));
                return Mono.error(new InvalidInputException(
                        "An eventId cannot be reused for a different status"));
            }
        }

        String requiredNext = switch (shipment.getStatus()) {
            case "CREATED" -> "PACKED";
            case "PACKED" -> "SHIPPED";
            case "SHIPPED" -> "IN_TRANSIT";
            case "IN_TRANSIT" -> "DELIVERED";
            default -> null;
        };
        if (!update.status().equals(requiredNext))
            return Mono.error(new InvalidInputException(
                    "Shipment milestone out of order or already delivered"));

        Instant now = Instant.now();
        List<ShipmentEvent> newHistory = new ArrayList<>(history);
        newHistory.add(new ShipmentEvent(update.eventId(), update.status(), now));
        shipment.setStatus(update.status());
        shipment.setHistory(newHistory);
        shipment.setUpdatedAt(now);
        return shipments.save(shipment).map(this::details);
    }

    private Mono<ShipmentDetails> lookup(String id) {
        return shipments.findById(id)
                .switchIfEmpty(Mono.error(new NotFoundException("Shipment not found")))
                .map(this::details);
    }

    private ShipmentDetails details(ShipmentEntity entity) {
        return new ShipmentDetails(entity.getOrderId(), entity.getId(), entity.getStatus(),
                entity.getTrackingNumber(), "MOCK_CARRIER", entity.getDestinationZone(),
                entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getHistory() == null ? List.of() : List.copyOf(entity.getHistory()));
    }

    private void validateId(String orderId) {
        if (orderId == null || !orderId.matches("[0-9a-f]{64}"))
            throw new InvalidInputException("Invalid order ID");
    }

    private void validateOwner(String ownerKey) {
        if (ownerKey == null || !ownerKey.matches("[0-9a-f]{64}"))
            throw new InvalidInputException("Invalid order owner");
    }
}
