package amazon.microservices.composite.product.outbox;

import amazon.api.event.Event;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * A bounded, audit-backed operator redrive of the ORIGINAL quarantined event.
 * Never creates events or touches consumers, inventory, orders or payments.
 */
@Service
public class OutboxRedriveService {
    private static final int MAX_REDRIVES = 3;
    private static final Set<String> REASONS =
            Set.of("BROKER_RESTORED", "CONSUMER_RESTORED", "OPERATOR_APPROVED");
    private static final Set<String> BINDINGS =
            Set.of("products-out-0", "recommendations-out-0", "reviews-out-0");

    private final ReactiveMongoTemplate mongo;
    private final TransactionalOperator transactions;
    private final ObjectMapper mapper;
    private final boolean enabled;

    public record Request(String reason) {}

    public OutboxRedriveService(ReactiveMongoTemplate mongo,
            TransactionalOperator outboxTransactions, ObjectMapper mapper,
            @Value("${app.outbox.quarantine.enabled:false}") boolean enabled) {
        this.mongo=mongo;
        this.transactions=outboxTransactions;
        this.mapper=mapper;
        this.enabled=enabled;
    }

    public Mono<List<OutboxRedriveRequest>> history(String eventId, int limit) {
        if (!valid(eventId) || limit < 1 || limit > 20)
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid event ID or limit (1-20)"));
        Query query = Query.query(Criteria.where("eventId").is(eventId))
                .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                .limit(limit);
        return mongo.findById(eventId, OutboxEntry.class)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown outbox event")))
                .then(mongo.find(query, OutboxRedriveRequest.class).collectList());
    }

    public Mono<OutboxRedriveRequest> request(String eventId, String key,
            Request body, Jwt actor) {
        if (!valid(eventId) || key == null
                || !key.matches("[a-zA-Z0-9._-]{8,100}")
                || body == null || !REASONS.contains(body.reason()))
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Valid event ID, Idempotency-Key and reason required"));
        if (actor == null || actor.getIssuer() == null
                || actor.getSubject() == null || actor.getSubject().isBlank())
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Authenticated operator identity required"));
        if (!enabled)
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Outbox quarantine and redrive are disabled"));

        String principal = sha256(actor.getIssuer() + "\u0000" + actor.getSubject());
        String requestId = sha256(eventId + "\u0000" + key);

        // A genuine duplicate is still idempotent after the first request
        // moved the event back to PENDING. A different actor/reason is rejected.
        return mongo.findById(requestId, OutboxRedriveRequest.class)
                .flatMap(saved -> identical(saved, eventId, principal, body.reason()))
                .switchIfEmpty(Mono.defer(() ->
                        create(eventId, requestId, principal, body.reason())
                                .onErrorResume(DuplicateKeyException.class,
                                        error -> mongo.findById(requestId, OutboxRedriveRequest.class)
                                                .switchIfEmpty(Mono.error(new ResponseStatusException(
                                                        HttpStatus.CONFLICT, "Redrive key collision")))
                                                .flatMap(saved -> identical(saved, eventId,
                                                        principal, body.reason())))));
    }

    private Mono<OutboxRedriveRequest> create(
            String eventId, String requestId, String principal, String reason) {
        return Mono.defer(() -> {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            // The ordered dispatcher never skips its oldest uncompleted entry.
            Query headQuery = Query.query(Criteria.where("state")
                            .in(List.of(OutboxEntry.PENDING, OutboxEntry.IN_FLIGHT,
                                    OutboxEntry.QUARANTINED)))
                    .with(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("_id")))
                    .limit(1);

            return mongo.findOne(headQuery, OutboxEntry.class)
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "No undelivered outbox head")))
                    .flatMap(head -> {
                        if (!eventId.equals(head.getId())
                                || !OutboxEntry.QUARANTINED.equals(head.getState()))
                            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Only the quarantined global queue head can be redriven"));
                        if (!validOriginal(head))
                            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Original event payload is not a valid tracked event"));
                        String payloadDigest = sha256(head.getPayloadJson());
                        Query existing = Query.query(Criteria.where("eventId").is(eventId));
                        return mongo.count(existing, OutboxRedriveRequest.class)
                                .flatMap(count -> {
                                    if (count >= MAX_REDRIVES)
                                        return Mono.error(new ResponseStatusException(
                                                HttpStatus.CONFLICT, "Redrive budget exhausted"));
                                    OutboxRedriveRequest saved = new OutboxRedriveRequest(
                                            requestId, eventId, principal, reason, payloadDigest,
                                            count.intValue()+1, now);
                                    Query compareAndSet = Query.query(new Criteria().andOperator(
                                            Criteria.where("_id").is(eventId),
                                            Criteria.where("state").is(OutboxEntry.QUARANTINED),
                                            Criteria.where("quarantinedAt").is(head.getQuarantinedAt()),
                                            Criteria.where("attempts").is(head.getAttempts())));
                                    Update reopen = new Update()
                                            .set("state", OutboxEntry.PENDING)
                                            .set("nextAttemptAt", now)
                                            .set("attempts", 0)
                                            .unset("quarantinedAt").unset("quarantineReason")
                                            .unset("leaseId").unset("leaseUntil");
                                    // Atomic intent + CAS in one MongoDB transaction:
                                    // concurrent operators cannot create two redrives.
                                    return mongo.insert(saved)
                                            .flatMap(intent -> mongo.updateFirst(
                                                            compareAndSet, reopen, OutboxEntry.class)
                                                    .flatMap(result -> result.getModifiedCount() == 1
                                                            ? Mono.just(intent)
                                                            : Mono.error(new ResponseStatusException(
                                                                    HttpStatus.CONFLICT,
                                                                    "Head changed; no redrive recorded"))));
                                });
                    }).as(transactions::transactional);
        });
    }

    private boolean validOriginal(OutboxEntry entry) {
        if (!BINDINGS.contains(entry.getBindingName())
                || entry.getPartitionKey() == null || entry.getPartitionKey() < 1
                || entry.getPayloadJson() == null || entry.getPayloadJson().length() > 128_000)
            return false;
        try {
            Event<?, ?> event = mapper.readValue(entry.getPayloadJson(), Event.class);
            return entry.getId().equals(event.getEventId())
                    && event.getEventType() != null
                    && String.valueOf(entry.getPartitionKey())
                            .equals(String.valueOf(event.getKey()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private Mono<OutboxRedriveRequest> identical(
            OutboxRedriveRequest saved, String eventId, String principal, String reason) {
        if (eventId.equals(saved.getEventId())
                && principal.equals(saved.getActorFingerprint())
                && reason.equals(saved.getReason()))
            return Mono.just(saved);
        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                "Idempotency-Key reused with a different operator or reason"));
    }

    private static boolean valid(String value) {
        return value != null && value.matches("[a-zA-Z0-9._-]{8,100}");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
