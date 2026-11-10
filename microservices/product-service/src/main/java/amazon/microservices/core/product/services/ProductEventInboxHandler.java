package amazon.microservices.core.product.services;

import amazon.api.core.product.Product;
import amazon.api.core.product.ProductService;
import amazon.api.event.Event;
import amazon.api.event.EventFingerprint;
import amazon.microservices.core.product.persistence.ProcessedProductEvent;
import amazon.microservices.core.product.persistence.ProcessedProductEventRepository;
import amazon.util.exceptions.EventProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** Product mutation + event receipt must commit in the same MongoDB transaction. */
@Component
public class ProductEventInboxHandler {
    private final ProductService products;
    private final ProductCatalogOperations catalog;
    private final ProcessedProductEventRepository inbox;
    private final TransactionalOperator tx;
    private final ObjectMapper mapper;

    public ProductEventInboxHandler(ProductService products, ProductCatalogOperations catalog,
                                    ProcessedProductEventRepository inbox,
                                    TransactionalOperator inventoryTransactions, ObjectMapper mapper) {
        this.products=products; this.catalog=catalog; this.inbox=inbox;
        this.tx=inventoryTransactions; this.mapper=mapper;
    }

    public void accept(Event<Integer, Product> event) {
        if (event == null || event.getEventType() == null || event.getKey() == null
                || event.getKey() < 1)
            throw new EventProcessingException("Invalid product event");
        validate(event);
        String id = EventFingerprint.checkedId(event);
        if (id == null) { // pre-deployment event without an ID
            operation(event).block();
            return;
        }
        String fingerprint = EventFingerprint.of(event, mapper);
        Mono<Void> processing = Mono.defer(() -> inbox.findById(id)
                .flatMap(previous -> {
                    if (!fingerprint.equals(previous.getFingerprint()))
                        return Mono.<Boolean>error(new EventProcessingException(
                                "Conflicting product event ID"));
                    return Mono.just(false);
                })
                .switchIfEmpty(inbox.save(new ProcessedProductEvent(id, fingerprint))
                        .thenReturn(true))
                .flatMap(shouldRun -> shouldRun ? operation(event) : Mono.<Void>empty()))
                .as(tx::transactional);
        processing.block();
    }

    private void validate(Event<Integer, Product> event) {
        if (event.getEventType() == Event.Type.CREATE || event.getEventType() == Event.Type.UPDATE) {
            Product p = event.getData();
            if (p == null || p.getProductId() != event.getKey())
                throw new EventProcessingException("Product event key/payload mismatch");
        } else if (event.getEventType() != Event.Type.DELETE) {
            throw new EventProcessingException("Unsupported product event");
        }
    }

    private Mono<Void> operation(Event<Integer, Product> event) {
        return switch (event.getEventType()) {
            case CREATE -> products.createProduct(event.getData()).then();
            case UPDATE -> catalog.updateProduct(event.getKey(), event.getData()).then();
            case DELETE -> products.deleteProduct(event.getKey()).then();
        };
    }
}
