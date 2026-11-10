package amazon.microservices.composite.product.outbox;

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
 * Explicit operator request to rerun read-only receipt checks.
 * Transactionally records the request and requeues supervision metadata.
 * Never changes outbox, order, payment or consumer state.
 */
@Service
public class SagaRecheckService {
    private static final int MAX_REQUESTS = 3;
    private static final int MAX_PAGE = 20;
    private static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Set<String> REASONS = Set.of(
            "EVIDENCE_UPDATED", "CONSUMER_RESTORED", "BROKER_RECOVERED", "OPERATOR_REVIEW");

    private final ReactiveMongoTemplate mongo;
    private final TransactionalOperator transactions;

    public record RecheckCommand(String reason) {}

    public SagaRecheckService(ReactiveMongoTemplate mongo,
                              TransactionalOperator outboxTransactions) {
        this.mongo = mongo;
        this.transactions = outboxTransactions;
    }

    public Mono<List<SagaRecheckRequest>> history(String operationId, int limit) {
        if (!valid(operationId) || limit < 1 || limit > MAX_PAGE)
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid operation ID or limit (1-20)"));
        Query query = Query.query(Criteria.where("operationId").is(operationId))
                .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                .limit(limit);
        return mongo.findById(operationId, SagaSupervision.class)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown supervised operation")))
                .then(mongo.find(query, SagaRecheckRequest.class).collectList());
    }

    public Mono<SagaRecheckRequest> request(String operationId, String key,
                                           RecheckCommand body, Jwt actor) {
        if (!valid(operationId) || key == null
                || !key.matches("[A-Za-z0-9._-]{8,100}")
                || body == null || !REASONS.contains(body.reason()))
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Valid operation, Idempotency-Key and reason required"));
        if (actor == null || actor.getIssuer() == null
                || actor.getSubject() == null || actor.getSubject().isBlank())
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Verified operator identity required"));

        String principal = digest(actor.getIssuer() + "\u0000" + actor.getSubject());
        String id = digest(operationId + "\u0000" + key);

        // Idempotency lookup deliberately precedes the NEEDS_REVIEW check:
        // identical retries remain valid after the first request requeues the case.
        return mongo.findById(id, SagaRecheckRequest.class)
                .flatMap(existing -> identical(existing, operationId, principal, body.reason()))
                .switchIfEmpty(Mono.defer(() -> create(operationId, id, principal, body.reason())
                        .onErrorResume(DuplicateKeyException.class, ex ->
                                mongo.findById(id, SagaRecheckRequest.class)
                                        .switchIfEmpty(Mono.error(new ResponseStatusException(
                                                HttpStatus.CONFLICT, "Recheck idempotency collision")))
                                        .flatMap(existing -> identical(existing, operationId,
                                                principal, body.reason())))));
    }

    private Mono<SagaRecheckRequest> create(String operationId, String id,
                                             String principal, String reason) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return mongo.findById(operationId, SagaSupervision.class)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown supervised operation")))
                .flatMap(row -> {
                    if (!SagaSupervision.NEEDS_REVIEW.equals(row.getState()))
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                "Only NEEDS_REVIEW operations can be rechecked"));
                    return mongo.count(Query.query(Criteria.where("operationId").is(operationId)),
                                    SagaRecheckRequest.class)
                            .flatMap(count -> {
                                if (count >= MAX_REQUESTS)
                                    return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                            "Recheck budget exhausted"));
                                SagaRecheckRequest intent = new SagaRecheckRequest(
                                        id, operationId, principal, reason, (int) count + 1, now);
                                Query eligible = Query.query(new Criteria().andOperator(
                                        Criteria.where("_id").is(operationId),
                                        Criteria.where("state").is(SagaSupervision.NEEDS_REVIEW),
                                        Criteria.where("generation").is(row.getGeneration())));
                                Update reopen = new Update()
                                        .set("state", SagaSupervision.PENDING)
                                        .set("nextCheckAt", now)
                                        .set("checks", 0)
                                        .inc("generation", 1)
                                        .unset("reviewReason")
                                        .unset("leaseId")
                                        .unset("leaseUntil");
                                // Request and requeue commit/rollback as ONE Mongo transaction.
                                // If a competing operator already reopened the case, rollback
                                // the request instead of manufacturing a successful recheck.
                                return mongo.insert(intent)
                                        .flatMap(saved -> mongo.updateFirst(eligible, reopen,
                                                        SagaSupervision.class)
                                                .flatMap(result -> result.getModifiedCount() == 1
                                                        ? Mono.just(saved)
                                                        : Mono.error(new ResponseStatusException(
                                                                HttpStatus.CONFLICT,
                                                                "Supervision state changed; retry with a new key"))))
                                        .as(transactions::transactional);
                            });
                });
    }

    private Mono<SagaRecheckRequest> identical(SagaRecheckRequest existing,
                                                String operationId, String principal,
                                                String reason) {
        if (operationId.equals(existing.getOperationId())
                && principal.equals(existing.getActorFingerprint())
                && reason.equals(existing.getReason()))
            return Mono.just(existing);
        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                "Idempotency-Key reused with different actor or reason"));
    }

    private static boolean valid(String id) {
        return id != null && id.matches(UUID_PATTERN);
    }

    private static String digest(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
