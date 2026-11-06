package amazon.microservices.core.product.services;

import amazon.api.commerce.MockPaymentEvent;
import amazon.api.commerce.OrderReceipt;
import amazon.util.exceptions.InvalidInputException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** The single webhook trust boundary. This endpoint must remain on the private network. */
@RestController
@RequestMapping("/internal/mock-payments")
public class MockPaymentWebhookController {
    private static final long MAX_SKEW_SECONDS = 300;
    private final OrderController orders;
    private final ObjectMapper mapper;
    private final String secret;

    public MockPaymentWebhookController(OrderController orders, ObjectMapper mapper,
                          @Value("${MOCK_PAYMENT_WEBHOOK_SECRET:}") String secret) {
        this.orders = orders;
        this.mapper = mapper;
        this.secret = secret;
    }

    @PostMapping(value = "/webhook", consumes = "application/json", produces = "application/json")
    public Mono<OrderReceipt> webhook(@RequestHeader("X-Mock-Timestamp") String timestamp,
                                     @RequestHeader("X-Mock-Signature") String signature,
                                     @RequestBody String rawBody) {
        verifySignature(timestamp, signature, rawBody);
        try {
            MockPaymentEvent event = mapper.readValue(rawBody, MockPaymentEvent.class);
            return orders.applyMockPaymentEvent(event);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new InvalidInputException("Invalid mock payment JSON");
        }
    }

    void verifySignature(String timestamp, String signature, String rawBody) {
        if (secret == null || secret.isBlank() || secret.length() < 32
                || secret.startsWith("replace-with"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Mock payment webhook secret is not configured");
        if (rawBody == null || rawBody.length() > 16384
                || timestamp == null || !timestamp.matches("[0-9]{1,12}")
                || signature == null || !signature.matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook timestamp");
        }
        long now = Instant.now().getEpochSecond();
        if (sentAt < now - MAX_SKEW_SECONDS || sentAt > now + MAX_SKEW_SECONDS)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook timestamp outside allowed window");
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            expected = mac.doFinal((timestamp + "." + rawBody).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC support unavailable", ex);
        }
        byte[] received = HexFormat.of().parseHex(signature);
        if (!MessageDigest.isEqual(expected, received))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
    }
}
