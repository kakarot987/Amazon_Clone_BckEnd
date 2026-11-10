package amazon.microservices.composite.product.outbox;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Operator-only triage notes are metadata, NOT repair/compensation requests.
 * Method-specific scope protection lives in SecurityConfig.
 */
@RestController
@RequestMapping("/product-composite/fulfillment")
public class SagaTriageController {
    private final SagaTriageService triage;

    public SagaTriageController(SagaTriageService triage) {
        this.triage = triage;
    }

    @GetMapping(value="/supervisions/review-queue", produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<SagaSupervision>> queue(@RequestParam(defaultValue="20") int limit) {
        return triage.queue(limit);
    }

    @GetMapping(value="/operations/{operationId}/triage", produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<SagaTriageNote>> notes(@PathVariable String operationId,
                                           @RequestParam(defaultValue="20") int limit) {
        return triage.notes(operationId, limit);
    }

    @PostMapping(value="/operations/{operationId}/triage",
            consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<SagaTriageNote> add(@PathVariable String operationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt actor,
            @RequestBody SagaTriageService.NoteRequest body) {
        return triage.addNote(operationId, idempotencyKey, body, actor);
    }
}
