package amazon.microservices.composite.product;

import amazon.api.core.product.Product;
import amazon.api.event.Event;
import amazon.microservices.composite.product.outbox.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** MongoDB replica-set tests for strict head barrier and audited operator redrive. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes={TestSecurityConfig.class},
        properties={"eureka.client.enabled=false","spring.cloud.config.enabled=false",
                "spring.cloud.compatibility-verifier.enabled=false",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
                "spring.main.allow-bean-definition-overriding=true",
                "spring.cloud.stream.defaultBinder=rabbit",
                "app.outbox.initial-delay-ms=600000","app.saga.enabled=false"})
@Import(TestChannelBinderConfiguration.class)
class OutboxQuarantineTests extends OutboxMongoTestBase {
    @Autowired private ReactiveMongoTemplate mongo;
    @Autowired private ObjectMapper mapper;
    @Autowired private TransactionalOperator outboxTransactions;
    @Autowired private OutboxStore store;

    @BeforeEach
    void clear() {
        mongo.remove(new Query(),OutboxRedriveRequest.class).block();
        mongo.remove(new Query(),OutboxEntry.class).block();
        mongo.remove(new Query(),AggregateOperation.class).block();
        mongo.remove(new Query(),SagaSupervision.class).block();
    }

    private OutboundEvent product(int id) {
        return new OutboundEvent("products-out-0",
                new Event<>(Event.Type.CREATE,id,new Product(id,"Synthetic",1,null)));
    }

    private static Jwt actor(String identity) {
        return Jwt.withTokenValue("test-only")
                .header("alg","none")
                .claim("iss","https://issuer.example")
                .subject(identity)
                .build();
    }

    private OutboxDispatcher dispatcher(StreamBridge broker, int maxAttempts) {
        OutboxDispatcher d=new OutboxDispatcher(mongo,mapper,broker,
                Schedulers.immediate(),new SimpleMeterRegistry());
        ReflectionTestUtils.setField(d,"quarantineEnabled",true);
        ReflectionTestUtils.setField(d,"quarantineAfterAttempts",maxAttempts);
        return d;
    }

    private OutboxRedriveService redrives(boolean enabled) {
        return new OutboxRedriveService(mongo,outboxTransactions,mapper,enabled);
    }

    private OutboxEntry first() {
        return mongo.find(Query.query(Criteria.where("state").ne(OutboxEntry.PUBLISHED)),
                        OutboxEntry.class)
                .sort((left,right)->left.getCreatedAt().compareTo(right.getCreatedAt()))
                .blockFirst();
    }

    @Test
    void quarantinedPoisonHeadBlocksLaterEventsUntilApprovedOriginalIdRetry() {
        store.enqueue(List.of(product(951),product(952))).block();
        OutboxEntry original=first();
        assertNotNull(original);
        String payload=original.getPayloadJson();
        String eventId=original.getId();
        StreamBridge broker=mock(StreamBridge.class);
        when(broker.send(anyString(),any())).thenReturn(false,true,true);
        OutboxDispatcher dispatcher=dispatcher(broker,1);

        dispatcher.dispatchOnce().block();
        OutboxEntry blocked=mongo.findById(eventId,OutboxEntry.class).block();
        assertNotNull(blocked);
        assertEquals(OutboxEntry.QUARANTINED,blocked.getState());
        assertEquals(1,blocked.getAttempts());
        assertEquals("BROKER_REJECTED",blocked.getQuarantineReason());
        assertNotNull(blocked.getQuarantinedAt());

        // Dispatcher must never overtake quarantined global head.
        dispatcher.dispatchOnce().block();
        verify(broker,times(1)).send(anyString(),any());
        assertEquals(1L,mongo.count(Query.query(Criteria.where("state")
                .is(OutboxEntry.QUARANTINED)),OutboxEntry.class).block());

        OutboxRedriveService service=redrives(true);
        OutboxRedriveRequest first=service.request(eventId,"operator-redrive-key-01",
                new OutboxRedriveService.Request("BROKER_RESTORED"),actor("oncall-a")).block();
        OutboxRedriveRequest repeated=service.request(eventId,"operator-redrive-key-01",
                new OutboxRedriveService.Request("BROKER_RESTORED"),actor("oncall-a")).block();
        assertNotNull(first);
        assertNotNull(repeated);
        assertEquals(first.getId(),repeated.getId());
        assertEquals(first.getCreatedAt(),repeated.getCreatedAt());
        assertEquals(1L,mongo.count(new Query(),OutboxRedriveRequest.class).block());

        OutboxEntry reopened=mongo.findById(eventId,OutboxEntry.class).block();
        assertNotNull(reopened);
        assertEquals(OutboxEntry.PENDING,reopened.getState());
        assertEquals(0,reopened.getAttempts());
        assertEquals(payload,reopened.getPayloadJson());
        assertEquals(eventId,reopened.getId());
        dispatcher.dispatchOnce().block();
        verify(broker,times(3)).send(anyString(),any());
        assertEquals(2L,mongo.count(Query.query(Criteria.where("state")
                .is(OutboxEntry.PUBLISHED)),OutboxEntry.class).block());
    }

    @Test
    void invalidPayloadOrNonHeadIsNeverRedriven() {
        store.enqueue(List.of(product(961),product(962))).block();
        List<OutboxEntry> entries=mongo.findAll(OutboxEntry.class).collectList().block();
        assertNotNull(entries);
        entries.sort((a,b)->a.getCreatedAt().compareTo(b.getCreatedAt()));
        OutboxEntry head=entries.get(0);
        OutboxEntry later=entries.get(1);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(later.getId())),
                new Update().set("state",OutboxEntry.QUARANTINED)
                        .set("quarantinedAt",Instant.now()),OutboxEntry.class).block();
        OutboxRedriveService service=redrives(true);
        assertThrows(ResponseStatusException.class, () ->
                service.request(later.getId(),"operator-redrive-key-02",
                        new OutboxRedriveService.Request("OPERATOR_APPROVED"),
                        actor("oncall-a")).block());

        mongo.updateFirst(Query.query(Criteria.where("_id").is(head.getId())),
                new Update().set("state",OutboxEntry.QUARANTINED)
                        .set("quarantinedAt",Instant.now())
                        .set("payloadJson","invalid-json"),
                OutboxEntry.class).block();
        assertThrows(ResponseStatusException.class, () ->
                service.request(head.getId(),"operator-redrive-key-03",
                        new OutboxRedriveService.Request("OPERATOR_APPROVED"),
                        actor("oncall-a")).block());
        assertEquals(0L,mongo.count(new Query(),OutboxRedriveRequest.class).block());
    }

    @Test
    void disabledModeAndChangedIdempotencyKeyContentFailClosed() {
        store.enqueue(List.of(product(971))).block();
        OutboxEntry original=first();
        assertNotNull(original);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(original.getId())),
                new Update().set("state",OutboxEntry.QUARANTINED)
                        .set("quarantinedAt",Instant.now()),
                OutboxEntry.class).block();
        assertThrows(ResponseStatusException.class, () ->
                redrives(false).request(original.getId(),"operator-redrive-key-04",
                        new OutboxRedriveService.Request("OPERATOR_APPROVED"),
                        actor("oncall-a")).block());
        OutboxRedriveService enabled=redrives(true);
        enabled.request(original.getId(),"operator-redrive-key-04",
                new OutboxRedriveService.Request("OPERATOR_APPROVED"),actor("oncall-a")).block();
        assertThrows(ResponseStatusException.class, () ->
                enabled.request(original.getId(),"operator-redrive-key-04",
                        new OutboxRedriveService.Request("BROKER_RESTORED"),
                        actor("oncall-a")).block());
        assertThrows(ResponseStatusException.class, () ->
                enabled.request(original.getId(),"operator-redrive-key-04",
                        new OutboxRedriveService.Request("OPERATOR_APPROVED"),
                        actor("oncall-b")).block());
        assertEquals(1L,mongo.count(new Query(),OutboxRedriveRequest.class).block());
    }

    @Test
    void noMoreThanThreeUniqueRedrivesOfSameEvent() {
        store.enqueue(List.of(product(981))).block();
        OutboxEntry original=first();
        assertNotNull(original);
        OutboxRedriveService service=redrives(true);
        for(int i=0;i<3;i++) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(original.getId())),
                    new Update().set("state",OutboxEntry.QUARANTINED)
                            .set("quarantinedAt",Instant.now()),
                    OutboxEntry.class).block();
            var receipt=service.request(original.getId(),"operator-redrive-budget-0"+i,
                    new OutboxRedriveService.Request("OPERATOR_APPROVED"),
                    actor("oncall-a")).block();
            assertNotNull(receipt);
            assertEquals(i+1,receipt.getSequence());
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(original.getId())),
                new Update().set("state",OutboxEntry.QUARANTINED)
                        .set("quarantinedAt",Instant.now()),
                OutboxEntry.class).block();
        assertThrows(ResponseStatusException.class, () ->
                service.request(original.getId(),"operator-redrive-budget-04",
                        new OutboxRedriveService.Request("OPERATOR_APPROVED"),
                        actor("oncall-a")).block());
        assertEquals(3L,mongo.count(new Query(),OutboxRedriveRequest.class).block());
    }
}
