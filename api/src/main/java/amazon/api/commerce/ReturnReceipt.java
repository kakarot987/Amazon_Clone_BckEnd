package amazon.api.commerce;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Mock refund represents simulated ledger state, not money movement. */
public record ReturnReceipt(String orderId, String state, String reasonCode,
                            BigDecimal refundAmount, String currency,
                            Instant createdAt, Instant updatedAt,
                            List<ReturnEvent> history) {}
