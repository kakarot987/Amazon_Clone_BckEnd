package amazon.microservices.core.review.services;

import amazon.api.event.ReceiptEvidence;
import amazon.api.event.ReceiptProbeAuth;
import amazon.microservices.core.review.persistence.ProcessedReviewEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/** HMAC-protected internal read of an inbox record committed with its business write. */
@RestController
public class ReviewEventReceiptController {
    private static final String BINDING = "reviews-out-0";
    private final ProcessedReviewEventRepository inbox;
    private final String secret;

    public ReviewEventReceiptController(ProcessedReviewEventRepository inbox,
            @Value("${APP_RECEIPT_PROBE_SECRET:}") String secret) {
        this.inbox = inbox;
        this.secret = secret;
    }

    @GetMapping(value = "/internal/event-receipts/{eventId}", produces = "application/json")
    public ReceiptEvidence find(@PathVariable String eventId,
            @RequestHeader("X-Receipt-Timestamp") String timestamp,
            @RequestHeader("X-Receipt-Signature") String signature) {
        authenticate(eventId, timestamp, signature);
        return inbox.findById(eventId)
                .map(receipt -> new ReceiptEvidence(receipt.getId(),
                        receipt.getFingerprint(), receipt.getProcessedAt()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void authenticate(String id, String timestamp, String signature) {
        if (!ReceiptProbeAuth.configured(secret))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        if (!ReceiptProbeAuth.verify(secret, BINDING, id, timestamp, signature, Instant.now()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
