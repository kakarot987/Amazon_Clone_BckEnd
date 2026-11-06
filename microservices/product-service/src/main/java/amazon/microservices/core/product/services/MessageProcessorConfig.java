package amazon.microservices.core.product.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import amazon.api.core.product.Product;
import amazon.api.core.product.ProductService;
import amazon.api.event.Event;
import amazon.util.exceptions.EventProcessingException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

@Configuration
public class MessageProcessorConfig {

    private static final Logger LOG = LoggerFactory.getLogger(MessageProcessorConfig.class);

    private final ProductService productService;
    private final ProductCatalogOperations productCatalogOperations;

    @Autowired
    public MessageProcessorConfig(ProductService productService, ProductCatalogOperations productCatalogOperations) {
        this.productService = productService;
        this.productCatalogOperations = productCatalogOperations;
    }

    @Bean
    public Consumer<Event<Integer, Product>> messageProcessor() {
        return event -> {
            LOG.info("Process message created at {}...", event.getEventCreatedAt());

            switch (event.getEventType()) {

                case CREATE:
                    Product product = event.getData();
                    LOG.info("Create product with ID: {}", product.getProductId());
                    productService.createProduct(product).block();
                    break;

                case UPDATE:
                    Product update = event.getData();
                    if (update == null || update.getProductId() != event.getKey()) {
                        throw new EventProcessingException("Invalid UPDATE event for product");
                    }
                    productCatalogOperations.updateProduct(update.getProductId(), update).block();
                    break;

                case DELETE:
                    int productId = event.getKey();
                    LOG.info("Delete product with ProductID: {}", productId);
                    productService.deleteProduct(productId).block();
                    break;

                default:
                    String errorMessage = "Incorrect event type: " + event.getEventType() + ", expected a CREATE or DELETE event";
                    LOG.warn(errorMessage);
                    throw new EventProcessingException(errorMessage);
            }

            LOG.info("Message processing done!");

        };
    }
}
