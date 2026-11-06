package amazon.microservices.core.product.services;

import amazon.microservices.core.product.persistence.OrderEntity;
import amazon.microservices.core.product.persistence.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;

/**
 * Periodically reconciles incomplete orders and expires unpaid reservations.
 * A bounded pass avoids overlapping scans; all transitions reload orders
 * and use optimistic version checks + atomic product reservation IDs.
 */
@Component
public class OrderRecoveryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(OrderRecoveryWorker.class);
    private final OrderRepository orders;
    private final OrderController controller;
    private final Duration interval;
    private final int batchSize;
    private Disposable task;

    public OrderRecoveryWorker(OrderRepository orders, OrderController controller,
                               @Value("${app.orders.recovery-interval:PT30S}") Duration interval,
                               @Value("${app.orders.recovery-batch-size:100}") int batchSize) {
        if (interval.isZero() || interval.isNegative())
            throw new IllegalArgumentException("Recovery interval must be positive");
        if (batchSize < 1 || batchSize > 1000)
            throw new IllegalArgumentException("Recovery batch size must be 1-1000");
        this.orders = orders;
        this.controller = controller;
        this.interval = interval;
        this.batchSize = batchSize;
    }

    @PostConstruct
    void start() {
        task = Flux.interval(interval, Schedulers.boundedElastic())
                .onBackpressureDrop()
                .concatMap(tick -> recoverOnce().onErrorResume(error -> {
                    LOG.error("Order recovery batch failed", error);
                    return reactor.core.publisher.Mono.empty();
                }))
                .subscribe();
    }

    @PreDestroy
    void stop() {
        if (task != null) task.dispose();
    }

    /** One bounded scan, available to integration tests and manual recovery. */
    public reactor.core.publisher.Mono<Void> recoverOnce() {
        Instant now = Instant.now();
        return orders.findAll()
                .filter(order -> needsReconciliation(order, now))
                .take(batchSize)
                .concatMap(order -> controller.reconcileOrder(order.getId(), now)
                        .onErrorResume(error -> {
                            LOG.warn("Could not reconcile order {}: {}", order.getId(), error.toString());
                            return reactor.core.publisher.Mono.empty();
                        }))
                .then();
    }

    static boolean needsReconciliation(OrderEntity order, Instant now) {
        String status = order.getStatus();
        if (OrderEntity.PENDING.equals(status)
                || OrderEntity.CANCELLATION_PENDING.equals(status)
                || OrderEntity.EXPIRATION_PENDING.equals(status)
                || OrderEntity.PAYMENT_CONFIRMING.equals(status)
                || OrderEntity.PAYMENT_FAILED_PENDING.equals(status))
            return true;
        return (OrderEntity.RESERVED.equals(status)
                || OrderEntity.PAYMENT_PENDING.equals(status))
                && order.getExpiresAt() != null && !order.getExpiresAt().isAfter(now);
    }
}
