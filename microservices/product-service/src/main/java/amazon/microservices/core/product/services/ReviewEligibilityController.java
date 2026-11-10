package amazon.microservices.core.product.services;

import amazon.microservices.core.product.persistence.OrderEntity;
import amazon.microservices.core.product.persistence.OrderRepository;
import amazon.util.exceptions.InvalidInputException;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/** Internal-only proof of MOCK_PAID order: never call from an untrusted client. */
@RestController
@RequestMapping("/internal/review-eligibility")
public class ReviewEligibilityController {
    private final OrderRepository orders;

    public ReviewEligibilityController(OrderRepository orders) { this.orders = orders; }

    @GetMapping("/{ownerKey}/product/{productId}")
    public Mono<Boolean> hasMockPurchase(@PathVariable String ownerKey, @PathVariable int productId) {
        if (ownerKey == null || !ownerKey.matches("[0-9a-f]{64}") || productId < 1)
            throw new InvalidInputException("Invalid review eligibility query");
        return orders.existsByOwnerKeyAndProductIdAndStatus(
                ownerKey, productId, OrderEntity.MOCK_PAID);
    }
}
