package amazon.microservices.composite.product.services;

import amazon.api.commerce.InventoryReconciliationReport;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Operator-only, read-only audit; no stock correction occurs here. */
@RestController
@RequestMapping("/product-composite/fulfillment/inventory")
public class InventoryReconciliationCompositeController {
    private final WebClient client;

    public InventoryReconciliationCompositeController(WebClient client) {
        this.client = client;
    }

    @GetMapping(value = "/reconciliation", produces = "application/json")
    public Mono<InventoryReconciliationReport> audit() {
        return client.get()
                .uri("http://product/internal/inventory/reconciliation")
                .retrieve().bodyToMono(InventoryReconciliationReport.class)
                .onErrorMap(WebClientResponseException.class, error -> {
                    HttpStatus status = HttpStatus.resolve(error.getStatusCode().value());
                    return status == null ? error
                            : new ResponseStatusException(status, "Inventory audit unavailable");
                });
    }
}
