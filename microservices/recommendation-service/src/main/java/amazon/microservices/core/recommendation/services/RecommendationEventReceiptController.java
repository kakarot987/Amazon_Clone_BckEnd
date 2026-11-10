package amazon.microservices.core.recommendation.services;

import amazon.api.event.ReceiptEvidence;
import amazon.api.event.ReceiptProbeAuth;
import amazon.microservices.core.recommendation.persistence.ProcessedRecommendationEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Instant;

/** HMAC-protected internal read of an inbox record committed with its business write. */
@RestController
public class RecommendationEventReceiptController {
    private static final String BINDING = "recommendations-out-0";
    private final ProcessedRecommendationEventRepository inbox;
    private final String secret;

    public RecommendationEventReceiptController(ProcessedRecommendationEventRepository inbox,
            @Value("${APP_RECEIPT_PROBE_SECRET:}") String secret) {
        this.inbox = inbox;
        this.secret = secret;
    }

    @GetMapping(value = "/internal/event-receipts/{eventId}", produces = "application/json")
    public Mono<ReceiptEvidence> find(@PathVariable String eventId,
            @RequestHeader("X-Receipt-Timestamp") String timestamp,
            @RequestHeader("X-Receipt-Signature") String signature) {
        authenticate(eventId, timestamp, signature);
        return inbox.findById(eventId)
                .map(receipt -> new ReceiptEvidence(receipt.getId(),
                        receipt.getFingerprint(), receipt.getProcessedAt()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    private void authenticate(String id, String timestamp, String signature) {
        if (!ReceiptProbeAuth.configured(secret))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        if (!ReceiptProbeAuth.verify(secret, BINDING, id, timestamp, signature, Instant.now()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
