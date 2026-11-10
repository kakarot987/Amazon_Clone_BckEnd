package amazon.microservices.composite.product.outbox;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Operator-only read: does not mutate saga state or replay any event. */
@RestController
@RequestMapping("/product-composite/fulfillment/operations")
public class ConsumerReceiptAuditController {
    private final ConsumerReceiptAuditService receipts;

    public ConsumerReceiptAuditController(ConsumerReceiptAuditService receipts) {
        this.receipts = receipts;
    }

    @GetMapping(value = "/{operationId}/receipts", produces = "application/json")
    public Mono<ConsumerReceiptAuditService.Audit> inspect(@PathVariable String operationId) {
        return receipts.inspect(operationId);
    }
}
