package amazon.microservices.core.product.services;

import com.mongodb.ReadConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * All stock/order settlement code must run against a MongoDB replica set
 * (or sharded cluster). We deliberately do NOT fall back to nontransactional
 * writes on standalone MongoDB: failure to support transactions is fatal
 * for checkout rather than silently weakening inventory guarantees.
 */
@Configuration(proxyBeanMethods = false)
public class InventoryTransactionConfig {

    @Bean
    public ReactiveMongoTransactionManager mongoTransactionManager(
            ReactiveMongoDatabaseFactory databaseFactory) {
        ReactiveMongoTransactionManager manager =
                new ReactiveMongoTransactionManager(databaseFactory);
        manager.setOptions(TransactionOptions.builder()
                .readConcern(ReadConcern.SNAPSHOT)
                .writeConcern(WriteConcern.MAJORITY)
                .build());
        return manager;
    }

    @Bean
    public TransactionalOperator inventoryTransactions(
            ReactiveMongoTransactionManager mongoTransactionManager) {
        return TransactionalOperator.create(mongoTransactionManager);
    }
}
