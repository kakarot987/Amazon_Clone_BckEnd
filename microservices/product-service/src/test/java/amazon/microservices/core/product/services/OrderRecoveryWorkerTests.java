package amazon.microservices.core.product.services;

import amazon.microservices.core.product.persistence.OrderEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class OrderRecoveryWorkerTests {
    private OrderEntity order(String state, Instant expiry) {
        OrderEntity order = new OrderEntity("order-1", "owner-1", 12, 2,
                "Product", new BigDecimal("19.99"), "USD", expiry);
        order.setStatus(state);
        return order;
    }

    @Test
    void unfinishedStatesAreAlwaysReconciled() {
        Instant now = Instant.parse("2026-11-06T16:00:00Z");
        assertTrue(OrderRecoveryWorker.needsReconciliation(
                order(OrderEntity.PENDING, now.plusSeconds(60)), now));
        assertTrue(OrderRecoveryWorker.needsReconciliation(
                order(OrderEntity.CANCELLATION_PENDING, now.plusSeconds(60)), now));
        assertTrue(OrderRecoveryWorker.needsReconciliation(
                order(OrderEntity.EXPIRATION_PENDING, now.plusSeconds(60)), now));
    }

    @Test
    void reconcilesInterruptedPaymentSettlementsAndPaymentTimeout() {
        Instant now = Instant.parse("2026-11-06T16:00:00Z");
        assertTrue(OrderRecoveryWorker.needsReconciliation(
                order(OrderEntity.PAYMENT_CONFIRMING, now.plusSeconds(90)), now));
        assertTrue(OrderRecoveryWorker.needsReconciliation(
                order(OrderEntity.PAYMENT_FAILED_PENDING, now.plusSeconds(90)), now));
        assertTrue(OrderRecoveryWorker.needsReconciliation(
                order(OrderEntity.PAYMENT_PENDING, now.minusSeconds(1)), now));
        assertFalse(OrderRecoveryWorker.needsReconciliation(
                order(OrderEntity.PAYMENT_PENDING, now.plusSeconds(5)), now));
        assertFalse(OrderRecoveryWorker.needsReconciliation(
                order(OrderEntity.MOCK_PAID, now.minusSeconds(5)), now));
        assertFalse(OrderRecoveryWorker.needsReconciliation(
                order(OrderEntity.PAYMENT_FAILED, now.minusSeconds(5)), now));
    }

    @Test
    void activeProcessingClaimIsNeverReconciledByWorker() {
        Instant now = Instant.parse("2026-11-10T08:35:00Z");
        OrderEntity claimed = order(OrderEntity.RESERVING, now.plusSeconds(300));
        claimed.setReservationLeaseId("worker-claim-a");
        claimed.setReservationLeaseUntil(now.plusSeconds(60));
        assertFalse(OrderRecoveryWorker.needsReconciliation(claimed, now));

        claimed.setReservationLeaseUntil(now);
        assertTrue(OrderRecoveryWorker.needsReconciliation(claimed, now));

        claimed.setReservationLeaseUntil(now.minusSeconds(1));
        assertTrue(OrderRecoveryWorker.needsReconciliation(claimed, now));

        claimed.setReservationLeaseUntil(null);
        assertTrue(OrderRecoveryWorker.needsReconciliation(claimed, now));
    }

    @Test
    void onlyExpiredReservationsTriggerRelease() {
        Instant now = Instant.parse("2026-11-06T16:00:00Z");
        assertFalse(OrderRecoveryWorker.needsReconciliation(
                order(OrderEntity.RESERVED, now.plusSeconds(1)), now));
        assertTrue(OrderRecoveryWorker.needsReconciliation(
                order(OrderEntity.RESERVED, now), now));
        assertTrue(OrderRecoveryWorker.needsReconciliation(
                order(OrderEntity.RESERVED, now.minusSeconds(1)), now));
        assertFalse(OrderRecoveryWorker.needsReconciliation(
                order(OrderEntity.EXPIRED, now.minusSeconds(1)), now));
        assertFalse(OrderRecoveryWorker.needsReconciliation(
                order(OrderEntity.CANCELLED, now.minusSeconds(1)), now));
    }
}
