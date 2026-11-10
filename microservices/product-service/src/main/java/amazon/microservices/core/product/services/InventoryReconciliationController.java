package amazon.microservices.core.product.services;

import amazon.api.commerce.InventoryDiscrepancy;
import amazon.api.commerce.InventoryReconciliationReport;
import amazon.microservices.core.product.persistence.OrderEntity;
import amazon.microservices.core.product.persistence.OrderRepository;
import amazon.microservices.core.product.persistence.ProductEntity;
import amazon.microservices.core.product.persistence.ProductRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Read-only operator diagnostics. Reports are bounded and not transactionally
 * consistent with live updates; never automatically alter stock from an audit.
 * Access this only through the scope-protected composite endpoint.
 */
@RestController
@RequestMapping("/internal/inventory")
public class InventoryReconciliationController {
    private static final int MAX_PRODUCTS = 100;
    private static final int MAX_ORDERS = 100;
    private static final Set<String> HOLD_STATES = Set.of(
            OrderEntity.RESERVED, OrderEntity.PAYMENT_PENDING,
            OrderEntity.PAYMENT_CONFIRMING, OrderEntity.RESERVING,
            OrderEntity.CANCELLATION_PENDING, OrderEntity.EXPIRATION_PENDING,
            OrderEntity.PAYMENT_FAILED_PENDING);
    private final ReactiveMongoTemplate mongo;
    private final ProductRepository products;
    private final OrderRepository orders;

    public InventoryReconciliationController(ReactiveMongoTemplate mongo,
                                             ProductRepository products,
                                             OrderRepository orders) {
        this.mongo = mongo;
        this.products = products;
        this.orders = orders;
    }

    @GetMapping("/reconciliation")
    public Mono<InventoryReconciliationReport> check() {
        Query productsQuery = new Query().with(Sort.by("productId")).limit(MAX_PRODUCTS + 1);
        Query ordersQuery = Query.query(Criteria.where("status")
                .in(OrderEntity.RESERVED, OrderEntity.PAYMENT_PENDING))
                .with(Sort.by("_id")).limit(MAX_ORDERS + 1);

        return Mono.zip(
                mongo.find(productsQuery, ProductEntity.class).collectList(),
                mongo.find(ordersQuery, OrderEntity.class).collectList())
                .flatMap(snapshot -> {
                    List<ProductEntity> allProducts = snapshot.getT1();
                    List<OrderEntity> allOrders = snapshot.getT2();
                    boolean truncated = allProducts.size() > MAX_PRODUCTS
                            || allOrders.size() > MAX_ORDERS;
                    List<ProductEntity> productPage = allProducts.stream()
                            .limit(MAX_PRODUCTS).toList();
                    List<OrderEntity> orderPage = allOrders.stream()
                            .limit(MAX_ORDERS).toList();
                    return Mono.zip(
                            Flux.fromIterable(productPage)
                                    .concatMap(this::checkProduct)
                                    .collectList(),
                            Flux.fromIterable(orderPage)
                                    .concatMap(this::checkOrder)
                                    .collectList())
                            .map(found -> {
                                List<InventoryDiscrepancy> findings = new ArrayList<>();
                                found.getT1().forEach(findings::addAll);
                                found.getT2().forEach(findings::addAll);
                                return new InventoryReconciliationReport(Instant.now(),
                                        productPage.size(), orderPage.size(), truncated,
                                        List.copyOf(findings));
                            });
                });
    }

    private Mono<List<InventoryDiscrepancy>> checkProduct(ProductEntity product) {
        List<InventoryDiscrepancy> local = new ArrayList<>();
        int id = product.getProductId();
        if (product.getStockQuantity() != null && product.getStockQuantity() < 0)
            local.add(new InventoryDiscrepancy(id, null, "NEGATIVE_STOCK",
                    "Available stock is negative"));
        List<String> tokens = product.getReservedOrderIds() == null
                ? List.of() : product.getReservedOrderIds();
        if (product.getStockQuantity() == null && !tokens.isEmpty())
            local.add(new InventoryDiscrepancy(id, null, "UNTRACKED_STOCK_WITH_HOLDS",
                    "Reservation tokens exist without initialized inventory"));

        return Flux.fromIterable(tokens)
                .concatMap(token -> {
                    if (token == null || !token.matches("[0-9a-f]{64}"))
                        return Mono.just(new InventoryDiscrepancy(id, token,
                                "INVALID_HOLD_TOKEN", "Malformed reservation identifier"));
                    return orders.findById(token)
                            .map(order -> {
                                if (!HOLD_STATES.contains(order.getStatus()))
                                    return new InventoryDiscrepancy(id, token,
                                            "TERMINAL_ORDER_HOLD",
                                            "Product still holds an order outside active reservation states");
                                if (order.getItems().stream().noneMatch(line -> line.productId() == id))
                                    return new InventoryDiscrepancy(id, token,
                                            "UNRELATED_ORDER_HOLD",
                                            "Held order manifest does not contain this product");
                                return (InventoryDiscrepancy) null;
                            })
                            .switchIfEmpty(Mono.just(new InventoryDiscrepancy(
                                    id, token, "ORPHAN_HOLD", "No order exists for held token")));
                })
                .filter(java.util.Objects::nonNull)
                .collectList()
                .map(found -> {
                    local.addAll(found);
                    return local;
                });
    }

    private Mono<List<InventoryDiscrepancy>> checkOrder(OrderEntity order) {
        return Flux.fromIterable(order.getItems())
                .concatMap(line -> products.findByProductId(line.productId())
                        .map(product -> {
                            List<String> holds = product.getReservedOrderIds();
                            if (holds == null || !holds.contains(order.getId()))
                                return new InventoryDiscrepancy(line.productId(), order.getId(),
                                        "MISSING_HOLD",
                                        "Reserved/payment-pending order has no product reservation token");
                            return (InventoryDiscrepancy) null;
                        })
                        .switchIfEmpty(Mono.just(new InventoryDiscrepancy(
                                line.productId(), order.getId(),
                                "MISSING_PRODUCT", "Active order product no longer exists"))))
                .filter(java.util.Objects::nonNull)
                .collectList();
    }
}
