package amazon.microservices.composite.product;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

/** Real MongoDB replica-set transactions for composite outbox tests. */
public abstract class OutboxMongoTestBase {
    private static final MongoDBContainer database=new MongoDBContainer("mongo:6.0.4");
    static { database.start(); }

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> database.getReplicaSetUrl("composite-outbox-test"));
    }
}
