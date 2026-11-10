package amazon.microservices.core.recommendation.services;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

/** Requires Mongo replica set; never decouple event receipt from mutation. */
@Configuration(proxyBeanMethods = false)
public class RecommendationEventTransactionConfig {
    @Bean
    ReactiveMongoTransactionManager recommendationEventTransactionManager(ReactiveMongoDatabaseFactory factory) {
        return new ReactiveMongoTransactionManager(factory);
    }
    @Bean
    TransactionalOperator recommendationEventTransactions(
            ReactiveMongoTransactionManager recommendationEventTransactionManager) {
        return TransactionalOperator.create(recommendationEventTransactionManager);
    }
}
