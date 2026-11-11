package amazon.microservices.composite.product.outbox;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Operator-only quarantine recovery: retries original event only.
 * No business compensation, order mutation, or arbitrary replay endpoint.
 */
@RestController
@RequestMapping("/product-composite/fulfillment/outbox")
public class OutboxRedriveController {
    private final OutboxRedriveService redrives;

    public OutboxRedriveController(OutboxRedriveService redrives) {
        this.redrives=redrives;
    }

    @GetMapping(value="/{eventId}/redrives", produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<OutboxRedriveRequest>> history(
            @PathVariable String eventId, @RequestParam(defaultValue="10") int limit) {
        return redrives.history(eventId,limit);
    }

    @PostMapping(value="/{eventId}/redrives",
            consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<OutboxRedriveRequest> request(
            @PathVariable String eventId,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Jwt actor,
            @RequestBody OutboxRedriveService.Request request) {
        return redrives.request(eventId,key,request,actor);
    }
}
