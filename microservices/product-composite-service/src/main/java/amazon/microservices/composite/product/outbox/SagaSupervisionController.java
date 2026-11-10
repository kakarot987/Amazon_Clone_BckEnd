package amazon.microservices.composite.product.outbox;

import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Scope-protected operator GET. Does not trigger rechecks or business writes. */
@RestController
@RequestMapping("/product-composite/fulfillment/operations")
public class SagaSupervisionController {
    private final ReactiveMongoTemplate mongo;
    public SagaSupervisionController(ReactiveMongoTemplate mongo){this.mongo=mongo;}

    @GetMapping(value="/{operationId}/supervision",produces="application/json")
    public Mono<SagaSupervision> inspect(@PathVariable String operationId) {
        if(operationId==null || !operationId.matches("[0-9a-fA-F-]{36}"))
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid operation ID"));
        return mongo.findById(operationId,SagaSupervision.class)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No supervision record for operation")));
    }
}
