package amazon.microservices.composite.product.outbox;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/** Operator-scoped read-only inspection; does not replay or compensate. */
@RestController
@RequestMapping("/product-composite/fulfillment")
public class AggregateOperationTrackingController {
    private final AggregateOperationTrackingService tracking;

    public AggregateOperationTrackingController(AggregateOperationTrackingService tracking) {
        this.tracking = tracking;
    }

    @GetMapping(value = "/aggregates/{productId}/operations", produces = "application/json")
    public Mono<List<AggregateOperationTrackingService.Report>> list(
            @PathVariable int productId, @RequestParam(defaultValue = "10") int limit) {
        return tracking.list(productId, limit);
    }

    @GetMapping(value = "/operations/{operationId}", produces = "application/json")
    public Mono<AggregateOperationTrackingService.Report> get(@PathVariable String operationId) {
        return tracking.get(operationId);
    }
}
