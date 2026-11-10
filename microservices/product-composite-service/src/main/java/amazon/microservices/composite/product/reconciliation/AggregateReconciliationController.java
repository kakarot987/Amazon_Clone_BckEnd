package amazon.microservices.composite.product.reconciliation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Privileged diagnostic endpoint only: never repairs or republishes an event.
 * Access is restricted to SCOPE_fulfillment:read by SecurityConfig.
 */
@RestController
@RequestMapping("/product-composite/fulfillment/aggregates")
public class AggregateReconciliationController {
    private final AggregateReconciliationService reconciliation;

    public AggregateReconciliationController(AggregateReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping(value = "/{productId}/reconciliation", produces = "application/json")
    public Mono<AggregateReconciliationService.Report> reconcile(@PathVariable int productId) {
        return reconciliation.inspect(productId);
    }
}
