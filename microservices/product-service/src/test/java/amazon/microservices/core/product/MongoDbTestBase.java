package amazon.microservices.core.product;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;

public abstract class MongoDbTestBase {
  private static MongoDBContainer database = new MongoDBContainer("mongo:6.0.4");
  
  static {
    database.start();
  } 
  
  @DynamicPropertySource
  static void setProperties(DynamicPropertyRegistry registry) {
    // MongoDBContainer initializes a replica set; transactions must be
    // exercised in CI, not silently bypassed on a standalone server.
    registry.add("spring.data.mongodb.uri", () -> database.getReplicaSetUrl("test"));
  }
}
