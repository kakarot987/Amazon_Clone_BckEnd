package amazon.microservices.composite.product.outbox;

import com.mongodb.ReadConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.reactive.TransactionalOperator;

/** Fail closed if MongoDB is not a replica set; no volatile publisher fallback. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
public class OutboxConfiguration {
    @Bean
    public ReactiveMongoTransactionManager outboxTransactionManager(
            ReactiveMongoDatabaseFactory factory) {
        ReactiveMongoTransactionManager manager = new ReactiveMongoTransactionManager(factory);
        manager.setOptions(TransactionOptions.builder()
                .readConcern(ReadConcern.SNAPSHOT)
                .writeConcern(WriteConcern.MAJORITY)
                .build());
        return manager;
    }

    @Bean
    public TransactionalOperator outboxTransactions(
            ReactiveMongoTransactionManager outboxTransactionManager) {
        return TransactionalOperator.create(outboxTransactionManager);
    }
}
