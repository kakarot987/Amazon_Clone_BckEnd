package amazon.microservices.core.product.services;

import amazon.api.core.product.Product;
import amazon.api.core.product.ProductPage;
import amazon.api.discovery.RelatedProducts;
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
import java.util.ArrayList;
import java.util.List;

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
            @RequestParam(required = false) Boolean inStock,
            @RequestParam(defaultValue = "productId") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        if (query != null && query.length() > 100)
            throw new InvalidInputException("query must be at most 100 characters");
        if (category != null && category.length() > 120)
            throw new InvalidInputException("category must be at most 120 characters");
        Sort ordering = switch (sort) {
            case "productId" -> Sort.by(Sort.Direction.ASC, "productId");
            case "productIdDesc" -> Sort.by(Sort.Direction.DESC, "productId");
            case "name" -> Sort.by(Sort.Direction.ASC, "name").and(Sort.by("productId"));
            case "stockDesc" -> Sort.by(Sort.Direction.DESC, "stockQuantity")
                    .and(Sort.by("productId"));
            default -> throw new InvalidInputException(
                    "sort must be productId, productIdDesc, name or stockDesc");
        };

        List<Criteria> conditions = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            String safe = Pattern.quote(query.trim());
            conditions.add(new Criteria().orOperator(
                    Criteria.where("name").regex(safe, "i"),
                    Criteria.where("description").regex(safe, "i"),
                    Criteria.where("sku").regex(safe, "i")));
        }
        if (category != null && !category.isBlank())
            conditions.add(Criteria.where("category").is(category.trim()));
        if (inStock != null)
            conditions.add(Boolean.TRUE.equals(inStock)
                    ? Criteria.where("stockQuantity").gt(0)
                    : new Criteria().orOperator(
                        Criteria.where("stockQuantity").lte(0),
                        Criteria.where("stockQuantity").is(null)));

        Query countQuery = conditions.isEmpty() ? new Query()
                : Query.query(new Criteria().andOperator(conditions.toArray(new Criteria[0])));
        Query resultsQuery = conditions.isEmpty() ? new Query()
                : Query.query(new Criteria().andOperator(conditions.toArray(new Criteria[0])));
        resultsQuery.with(ordering).skip((long) page * size).limit(size);
        return Mono.zip(
                template.find(resultsQuery, ProductEntity.class)
                        .map(mapper::entityToApi)
                        .map(this::withAddress)
                        .collectList(),
                template.count(countQuery, ProductEntity.class))
                .map(tuple -> new ProductPage(tuple.getT1(), page, size, tuple.getT2()));
    }

    /**
     * Simple deterministic, category-based discovery, not personalized ranking.
     * Excludes the source product and does not claim availability or similarity scores.
     */
    @GetMapping("/products/{productId}/related")
    public Mono<RelatedProducts> relatedProducts(@PathVariable int productId,
                                                  @RequestParam(defaultValue = "6") int limit) {
        if (productId < 1 || limit < 1 || limit > 20)
            throw new InvalidInputException("productId must be positive and limit must be 1-20");
        return repository.findByProductId(productId)
                .switchIfEmpty(Mono.error(new NotFoundException(
                        "No product found for productId: " + productId)))
                .flatMap(source -> {
                    if (source.getCategory() == null || source.getCategory().isBlank())
                        return Mono.just(new RelatedProducts(productId, "shared-category", List.of()));
                    Query similar = Query.query(new Criteria().andOperator(
                            Criteria.where("category").is(source.getCategory()),
                            Criteria.where("productId").ne(productId)))
                            .with(Sort.by("productId")).limit(limit);
                    return template.find(similar, ProductEntity.class)
                            .map(mapper::entityToApi).map(this::withAddress)
                            .collectList()
                            .map(items -> new RelatedProducts(productId, "shared-category", items));
                });
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new InvalidInputException("page must be >= 0 and size must be 1-100");
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
                    // A stale catalog write must not erase live reservations.
                    if (existing.getReservedOrderIds() != null && !existing.getReservedOrderIds().isEmpty()) {
                        return Mono.error(new InvalidInputException(
                                "Cannot update a product with active order reservations"));
                    }
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
