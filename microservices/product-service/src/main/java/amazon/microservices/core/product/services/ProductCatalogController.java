package amazon.microservices.core.product.services;

import amazon.api.core.product.Product;
import amazon.api.core.product.ProductPage;
import amazon.microservices.core.product.persistence.ProductEntity;
import amazon.microservices.core.product.persistence.ProductRepository;
import amazon.util.exceptions.InvalidInputException;
import amazon.util.exceptions.NotFoundException;
import amazon.util.http.ServiceUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**
 * Internal catalog API. Public traffic should go through the protected
 * composite API and Gateway; do not expose this service's port externally.
 */
@RestController
public class ProductCatalogController implements ProductCatalogOperations {
    private final ReactiveMongoTemplate template;
    private final ProductRepository repository;
    private final ProductMapper mapper;
    private final ServiceUtil serviceUtil;

    public ProductCatalogController(ReactiveMongoTemplate template, ProductRepository repository,
                                    ProductMapper mapper, ServiceUtil serviceUtil) {
        this.template = template;
        this.repository = repository;
        this.mapper = mapper;
        this.serviceUtil = serviceUtil;
    }

    @GetMapping("/products")
    public Mono<ProductPage> listProducts(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100 || ((long) page * size) > Integer.MAX_VALUE) {
            throw new InvalidInputException("page must be >= 0 and size must be 1-100");
        }
        if (query != null && query.length() > 100) {
            throw new InvalidInputException("query must be at most 100 characters");
        }
        if (category != null && category.length() > 120) {
            throw new InvalidInputException("category must be at most 120 characters");
        }

        Criteria criteria = new Criteria();
        if (query != null && !query.isBlank()) {
            String safe = Pattern.quote(query.trim());
            criteria = criteria.orOperator(Criteria.where("name").regex(safe, "i"),
                Criteria.where("description").regex(safe, "i"),
                Criteria.where("sku").regex(safe, "i"));
        }
        if (category != null && !category.isBlank()) {
            criteria = criteria.and("category").is(category.trim());
        }
        Query countQuery = Query.query(criteria);
        Query resultsQuery = Query.query(criteria)
                .with(Sort.by(Sort.Direction.ASC, "productId"))
                .skip((long) page * size)
                .limit(size);
        return Mono.zip(
                template.find(resultsQuery, ProductEntity.class)
                        .map(mapper::entityToApi)
                        .map(this::withAddress)
                        .collectList(),
                template.count(countQuery, ProductEntity.class))
                .map(tuple -> new ProductPage(tuple.getT1(), page, size, tuple.getT2()));
    }

    @PutMapping(value = "/product/{productId}", consumes = "application/json")
    @Override
    public Mono<Product> updateProduct(@PathVariable int productId, @RequestBody Product product) {
        if (product == null || product.getProductId() != productId)
            throw new InvalidInputException("path productId must match request body productId");
        ProductValidation.validate(product);
        return repository.findByProductId(productId)
                .switchIfEmpty(Mono.error(new NotFoundException("No product found for productId: " + productId)))
                .flatMap(existing -> {
                    existing.setName(product.getName());
                    existing.setWeight(product.getWeight());
                    existing.setSku(product.getSku());
                    existing.setDescription(product.getDescription());
                    existing.setCategory(product.getCategory());
                    existing.setPrice(product.getPrice());
                    existing.setCurrency(product.getCurrency());
                    existing.setStockQuantity(product.getStockQuantity());
                    existing.setImageUrls(product.getImageUrls());
                    return repository.save(existing);
                })
                .onErrorMap(OptimisticLockingFailureException.class,
                    ex -> new InvalidInputException("Concurrent product update; please retry"))
                .onErrorMap(DuplicateKeyException.class,
                    ex -> new InvalidInputException("Duplicate product data"))
                .map(mapper::entityToApi)
                .map(this::withAddress);
    }

    private Product withAddress(Product product) {
        product.setServiceAddress(serviceUtil.getServiceAddress());
        return product;
    }
}
