package amazon.microservices.composite.product.outbox;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Operator incident queue and idempotent, append-only investigation notes.
 * This service deliberately NEVER changes SagaSupervision or any business data.
 */
@Service
public class SagaTriageService {
    private static final int MAX_PAGE = 50;
    private static final String OPERATION_ID =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Set<String> CATEGORIES = Set.of(
            "INVESTIGATING", "EVIDENCE_COLLECTED", "ESCALATED", "FOLLOW_UP_REQUIRED");

    private final ReactiveMongoTemplate mongo;

    public SagaTriageService(ReactiveMongoTemplate mongo) {
        this.mongo = mongo;
    }

    public record NoteRequest(String category, String note) {}

    /** Only actionable, terminal NEEDS_REVIEW records are shown in this queue. */
    public Mono<List<SagaSupervision>> queue(int limit) {
        if (limit < 1 || limit > MAX_PAGE)
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be 1-50"));
        Query query = Query.query(Criteria.where("state").is(SagaSupervision.NEEDS_REVIEW))
                .with(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("_id")))
                .limit(limit);
        return mongo.find(query, SagaSupervision.class).collectList();
    }

    public Mono<List<SagaTriageNote>> notes(String operationId, int limit) {
        if (!validId(operationId) || limit < 1 || limit > MAX_PAGE)
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid operation ID or limit (1-50)"));
        Query query = Query.query(Criteria.where("operationId").is(operationId))
                .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                .limit(limit);
        return mongo.findById(operationId, SagaSupervision.class)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown supervised operation")))
                .then(mongo.find(query, SagaTriageNote.class).collectList());
    }

    public Mono<SagaTriageNote> addNote(String operationId, String idempotencyKey,
                                      NoteRequest body, Jwt actor) {
        if (!validId(operationId) || idempotencyKey == null
                || !idempotencyKey.matches("[A-Za-z0-9._-]{8,100}"))
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Valid operation ID and Idempotency-Key are required"));
        if (body == null || !CATEGORIES.contains(body.category()) || body.note() == null)
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid triage category or note"));
        String clean = body.note().trim();
        if (clean.length() < 16 || clean.length() > 500
                || clean.chars().anyMatch(Character::isISOControl))
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Note must be 16-500 characters without control characters"));

        if (actor == null || actor.getIssuer() == null
                || actor.getSubject() == null || actor.getSubject().isBlank())
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Verified operator identity is required"));
        // Never store a raw subject, token, email, or other identity claims.
        String actorFingerprint = sha256(actor.getIssuer() + "\u0000" + actor.getSubject());
        String noteId = sha256(operationId + "\u0000" + idempotencyKey);
        SagaTriageNote proposed = new SagaTriageNote(noteId, operationId, actorFingerprint,
                body.category(), clean, Instant.now());

        return mongo.findById(operationId, SagaSupervision.class)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown supervised operation")))
                .flatMap(supervision -> {
                    if (!SagaSupervision.NEEDS_REVIEW.equals(supervision.getState()))
                        return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                "Only NEEDS_REVIEW operations can receive triage notes"));
                    return mongo.insert(proposed)
                            .onErrorResume(DuplicateKeyException.class,
                                    ex -> mongo.findById(noteId, SagaTriageNote.class)
                                            .switchIfEmpty(Mono.error(new ResponseStatusException(
                                                    HttpStatus.CONFLICT, "Triage key collision")))
                                            .flatMap(existing -> {
                                                boolean same = operationId.equals(existing.getOperationId())
                                                        && actorFingerprint.equals(existing.getActorFingerprint())
                                                        && body.category().equals(existing.getCategory())
                                                        && clean.equals(existing.getNote());
                                                return same ? Mono.just(existing)
                                                        : Mono.error(new ResponseStatusException(
                                                                HttpStatus.CONFLICT,
                                                                "Idempotency-Key reused with different triage content"));
                                            }));
                });
    }

    private static boolean validId(String id) {
        return id != null && id.matches(OPERATION_ID);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
