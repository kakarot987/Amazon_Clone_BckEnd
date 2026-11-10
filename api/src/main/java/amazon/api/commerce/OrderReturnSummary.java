package amazon.api.commerce;

import java.math.BigDecimal;
import java.time.Instant;

/** Simulated return/refund status; no real funds move. */
public record OrderReturnSummary(String state, BigDecimal mockRefundAmount,
                                 String currency, Instant updatedAt) {}
