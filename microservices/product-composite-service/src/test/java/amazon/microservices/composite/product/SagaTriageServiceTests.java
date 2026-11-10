package amazon.microservices.composite.product;

import amazon.microservices.composite.product.outbox.SagaSupervision;
import amazon.microservices.composite.product.outbox.SagaTriageNote;
import amazon.microservices.composite.product.outbox.SagaTriageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes={TestSecurityConfig.class},
        properties={"eureka.client.enabled=false","spring.cloud.config.enabled=false",
                "spring.cloud.compatibility-verifier.enabled=false",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
                "spring.main.allow-bean-definition-overriding=true",
                "spring.cloud.stream.defaultBinder=rabbit",
                "app.outbox.initial-delay-ms=600000", "app.saga.enabled=false"})
@Import(TestChannelBinderConfiguration.class)
class SagaTriageServiceTests extends OutboxMongoTestBase {
    @Autowired private SagaTriageService triage;
    @Autowired private ReactiveMongoTemplate mongo;

    @BeforeEach
    void clear() {
        mongo.remove(new Query(), SagaTriageNote.class).block();
        mongo.remove(new Query(), SagaSupervision.class).block();
    }

    private static Jwt operator(String subject) {
        return Jwt.withTokenValue("test-only")
                .header("alg", "none")
                .claim("iss", "https://issuer.example")
                .subject(subject)
                .build();
    }

    private String seed(String state) {
        String id=UUID.randomUUID().toString();
        SagaSupervision row=new SagaSupervision(id,Instant.now());
        row.setState(state);
        mongo.insert(row).block();
        return id;
    }

    private SagaTriageService.NoteRequest request(String note) {
        return new SagaTriageService.NoteRequest("INVESTIGATING",note);
    }

    @Test
    void sameIdempotencyKeyAndContentReturnOriginalNoteOnly() {
        String id=seed(SagaSupervision.NEEDS_REVIEW);
        var first=triage.addNote(id,"operator-case-12345678",
                request("Investigating committed event receipts."),operator("oncall-1")).block();
        var second=triage.addNote(id,"operator-case-12345678",
                request("Investigating committed event receipts."),operator("oncall-1")).block();
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.getId(),second.getId());
        assertEquals(first.getCreatedAt(),second.getCreatedAt());
        assertEquals(1L,mongo.count(new Query(),SagaTriageNote.class).block());
        assertEquals(1,triage.notes(id,20).block().size());
        assertNotEquals("oncall-1",first.getActorFingerprint());
        assertEquals(SagaSupervision.NEEDS_REVIEW,
                mongo.findById(id,SagaSupervision.class).block().getState());
    }

    @Test
    void reusedKeyWithDifferentContentOrActorIsRejected() {
        String id=seed(SagaSupervision.NEEDS_REVIEW);
        triage.addNote(id,"operator-case-12345678",
                request("Initial investigation details."),operator("oncall-1")).block();
        assertThrows(ResponseStatusException.class, () ->
                triage.addNote(id,"operator-case-12345678",
                        request("Second conflicting investigation."),operator("oncall-1")).block());
        assertThrows(ResponseStatusException.class, () ->
                triage.addNote(id,"operator-case-12345678",
                        request("Initial investigation details."),operator("oncall-2")).block());
        assertEquals(1L,mongo.count(new Query(),SagaTriageNote.class).block());
    }

    @Test
    void onlyNeedsReviewMayReceiveNotesAndAppearInQueue() {
        String flagged=seed(SagaSupervision.NEEDS_REVIEW);
        String waiting=seed(SagaSupervision.WAITING);
        assertEquals(flagged,triage.queue(20).block().get(0).getId());
        assertEquals(1,triage.queue(20).block().size());
        assertThrows(ResponseStatusException.class, () ->
                triage.addNote(waiting,"operator-case-12345678",
                        request("Not eligible for investigation notes."),operator("oncall-1")).block());
        assertEquals(0L,mongo.count(new Query(),SagaTriageNote.class).block());
    }

    @Test
    void rejectsInvalidInputAndUnknownOperations() {
        String id=seed(SagaSupervision.NEEDS_REVIEW);
        assertThrows(ResponseStatusException.class, () -> triage.queue(51).block());
        assertThrows(ResponseStatusException.class, () -> triage.notes(id,0).block());
        assertThrows(ResponseStatusException.class, () ->
                triage.addNote(id,"short",
                        request("Investigating valid issue."),operator("oncall-1")).block());
        assertThrows(ResponseStatusException.class, () ->
                triage.addNote(id,"operator-case-12345678",
                        request("too short"),operator("oncall-1")).block());
        assertThrows(ResponseStatusException.class, () ->
                triage.addNote(id,"operator-case-12345678",
                        request("An otherwise valid note."),null).block());
        assertThrows(ResponseStatusException.class, () ->
                triage.addNote(UUID.randomUUID().toString(),"operator-case-12345678",
                        request("Investigating missing case."),operator("oncall-1")).block());
        assertEquals(0L,mongo.count(new Query(),SagaTriageNote.class).block());
    }
}
