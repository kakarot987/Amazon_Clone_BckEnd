package amazon.microservices.core.product.services;

import amazon.api.commerce.InventoryLevel;
import amazon.api.commerce.StockAdjustment;
import amazon.microservices.core.product.persistence.ProductEntity;
import amazon.microservices.core.product.persistence.ProductRepository;
import amazon.util.exceptions.InvalidInputException;
import amazon.util.exceptions.NotFoundException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * Atomic available-stock correction. This is NOT a checkout reservation.
 * Use a durable reservation ledger and idempotency keys in the orders phase.
 */
@RestController
@RequestMapping("/internal/inventory")
public class InventoryController {
    private final ReactiveMongoTemplate template;
    private final ProductRepository products;

    public InventoryController(ReactiveMongoTemplate template, ProductRepository products) {
        this.template = template;
        this.products = products;
    }

    @GetMapping("/{productId}")
    public Mono<InventoryLevel> getAvailability(@PathVariable int productId) {
        validateId(productId);
        return products.findByProductId(productId)
                .switchIfEmpty(Mono.error(new NotFoundException("No product found for productId: " + productId)))
                .map(product -> {
                    if (product.getStockQuantity() == null)
                        throw new InvalidInputException("Inventory is not initialized for this product");
                    return new InventoryLevel(productId, product.getStockQuantity());
                });
    }

    @PostMapping(value = "/{productId}/adjustments", consumes = "application/json")
    public Mono<InventoryLevel> adjustStock(@PathVariable int productId,
                                             @RequestBody StockAdjustment adjustment) {
        validateId(productId);
        if (adjustment == null || adjustment.getDelta() == 0
                || adjustment.getDelta() < -100000 || adjustment.getDelta() > 100000) {
            throw new InvalidInputException("delta must be between -100000 and 100000, excluding zero");
        }
        int delta = adjustment.getDelta();

        // Atomic compare-and-increment prevents stock from becoming negative
        // and avoids integer overflow. Untracked (null) stock is rejected.
        Criteria criteria = Criteria.where("productId").is(productId);
        if (delta < 0) {
            criteria = criteria.and("stockQuantity").gte(-delta);
        } else {
            criteria = criteria.and("stockQuantity").gte(0).lte(Integer.MAX_VALUE - delta);
        }

        return template.findAndModify(Query.query(criteria),
                        new Update().inc("stockQuantity", delta),
                        FindAndModifyOptions.options().returnNew(true), ProductEntity.class)
                .map(updated -> new InventoryLevel(productId, updated.getStockQuantity()))
                .switchIfEmpty(products.findByProductId(productId)
                        .flatMap(existing -> Mono.<InventoryLevel>error(
                                new InvalidInputException("Insufficient, untracked, or overflowed stock")))
                        .switchIfEmpty(Mono.error(
                                new NotFoundException("No product found for productId: " + productId))));
    }

    private void validateId(int productId) {
        if (productId < 1) throw new InvalidInputException("Invalid productId: " + productId);
    }
}
