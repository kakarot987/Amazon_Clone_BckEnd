package amazon.microservices.composite.product.outbox;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Explicit operator actions recheck evidence only. They cannot send broker
 * messages, change business data, refund, or compensate.
 */
@RestController
@RequestMapping("/product-composite/fulfillment")
public class RecoveryOperationsController {
    private final SagaRecheckService rechecks;
    private final RecoveryOperationsDashboard dashboard;

    public RecoveryOperationsController(SagaRecheckService rechecks,
                                        RecoveryOperationsDashboard dashboard) {
        this.rechecks=rechecks; this.dashboard=dashboard;
    }

    @GetMapping(value="/recovery/overview",produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<RecoveryOperationsDashboard.Overview> overview() {
        return dashboard.get();
    }

    @GetMapping(value="/operations/{operationId}/rechecks",
            produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<SagaRecheckRequest>> history(
            @PathVariable String operationId,@RequestParam(defaultValue="10") int limit) {
        return rechecks.history(operationId,limit);
    }

    @PostMapping(value="/operations/{operationId}/rechecks",
            consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<SagaRecheckRequest> request(
            @PathVariable String operationId,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Jwt actor,
            @RequestBody SagaRecheckService.RecheckCommand command) {
        return rechecks.request(operationId,key,command,actor);
    }
}
