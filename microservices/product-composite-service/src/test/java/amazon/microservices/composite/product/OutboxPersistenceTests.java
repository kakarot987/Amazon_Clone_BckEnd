package amazon.microservices.composite.product;

import amazon.api.core.product.Product;
import amazon.api.event.Event;
import amazon.microservices.composite.product.outbox.OutboundEvent;
import amazon.microservices.composite.product.outbox.OutboxDispatcher;
import amazon.microservices.composite.product.outbox.OutboxEntry;
import amazon.microservices.composite.product.outbox.OutboxStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;

import static amazon.api.event.Event.Type.CREATE;
import static amazon.api.event.Event.Type.DELETE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes={TestSecurityConfig.class},
        properties={"eureka.client.enabled=false","spring.cloud.config.enabled=false",
                "spring.cloud.compatibility-verifier.enabled=false",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
                "spring.main.allow-bean-definition-overriding=true",
                "spring.cloud.stream.defaultBinder=rabbit",
                "app.outbox.initial-delay-ms=600000"})
@Import(TestChannelBinderConfiguration.class)
class OutboxPersistenceTests extends OutboxMongoTestBase {
    @Autowired private OutboxStore store;
    @Autowired private ReactiveMongoTemplate mongo;
    @Autowired private ObjectMapper mapper;

    @BeforeEach
    void reset() {
        mongo.remove(new Query(),OutboxEntry.class).block();
    }

    private OutboundEvent product(int id, String name) {
        return new OutboundEvent("products-out-0",
                new Event<>(CREATE,id,new Product(id,name,1,null)));
    }

    private long count() {
        return mongo.count(new Query(),OutboxEntry.class).block();
    }

    @Test
    void entireAggregateBatchIsDurableBeforeAnyPublication() {
        store.enqueue(List.of(product(801,"A"),
                new OutboundEvent("recommendations-out-0",new Event<>(DELETE,801,null)),
                new OutboundEvent("reviews-out-0",new Event<>(DELETE,801,null)))).block();
        assertEquals(3L,count());
        List<OutboxEntry> saved=mongo.findAll(OutboxEntry.class).collectList().block();
        assertNotNull(saved);
        assertTrue(saved.stream().allMatch(e -> OutboxEntry.PENDING.equals(e.getState())));
        assertEquals(3,saved.stream().map(OutboxEntry::getId).distinct().count());
    }

    @Test
    void invalidAndDuplicateBatchCannotPersistPartialWrite() {
        assertThrows(IllegalArgumentException.class,
                () -> store.enqueue(List.of(product(811,"A"),
                        new OutboundEvent("arbitrary-binding",new Event<>(DELETE,811,null)))).block());
        assertEquals(0L,count());

        String shared="duplicate-aggregate-outbox-811";
        Event<Integer,Product> first=new Event<>(CREATE,811,new Product(811,"A",1,null),shared);
        Event<Integer,Product> second=new Event<>(CREATE,811,new Product(811,"B",1,null),shared);
        assertThrows(RuntimeException.class,() -> store.enqueue(List.of(
                new OutboundEvent("products-out-0",first),
                new OutboundEvent("products-out-0",second))).block());
        assertEquals(0L,count(),"MongoDB transaction must roll back first insert");
    }

    @Test
    void brokerRejectionLeavesOriginalEventRetryableAndBlocksFollowingEvents() {
        store.enqueue(List.of(product(821,"First"),product(822,"Second"))).block();
        StreamBridge broker=mock(StreamBridge.class);
        when(broker.send(anyString(),any())).thenReturn(false,true,true);
        OutboxDispatcher dispatcher=new OutboxDispatcher(mongo,mapper,broker,
                Schedulers.immediate(),new SimpleMeterRegistry());

        dispatcher.dispatchOnce().block();
        verify(broker,times(1)).send(anyString(),any());
        long pending=mongo.count(Query.query(Criteria.where("state")
                .is(OutboxEntry.PENDING)),OutboxEntry.class).block();
        assertEquals(2L,pending);
        dispatcher.dispatchOnce().block();
        verify(broker,times(1)).send(anyString(),any());

        mongo.updateMulti(Query.query(Criteria.where("state").is(OutboxEntry.PENDING)),
                new Update().set("nextAttemptAt",Instant.now().minusSeconds(1)),
                OutboxEntry.class).block();
        dispatcher.dispatchOnce().block();
        verify(broker,times(3)).send(anyString(),any());
        assertEquals(2L,mongo.count(Query.query(Criteria.where("state")
                .is(OutboxEntry.PUBLISHED)),OutboxEntry.class).block());
    }

    @Test
    void activeLeasePreventsDuplicateConcurrentPublicationUntilExpiry() {
        store.enqueue(List.of(product(831,"Leased"))).block();
        OutboxEntry pending=mongo.findOne(new Query(),OutboxEntry.class).block();
        assertNotNull(pending);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(pending.getId())),
                new Update().set("state",OutboxEntry.IN_FLIGHT)
                        .set("leaseId","abandoned-worker")
                        .set("leaseUntil",Instant.now().plusSeconds(120)),
                OutboxEntry.class).block();

        StreamBridge broker=mock(StreamBridge.class);
        when(broker.send(anyString(),any())).thenReturn(true);
        OutboxDispatcher dispatcher=new OutboxDispatcher(mongo,mapper,broker,
                Schedulers.immediate(),new SimpleMeterRegistry());
        dispatcher.dispatchOnce().block();
        verifyNoInteractions(broker);

        mongo.updateFirst(Query.query(Criteria.where("_id").is(pending.getId())),
                new Update().set("leaseUntil",Instant.now().minusSeconds(1)),
                OutboxEntry.class).block();
        dispatcher.dispatchOnce().block();
        verify(broker,times(1)).send(anyString(),any());
        OutboxEntry done=mongo.findById(pending.getId(),OutboxEntry.class).block();
        assertEquals(OutboxEntry.PUBLISHED,done.getState());
    }
}
