package amazon.api.commerce;

import java.time.Instant;
import java.util.List;

/** Bounded diagnostic snapshot. Incomplete scans must not be treated as clean. */
public record InventoryReconciliationReport(Instant checkedAt, int scannedProducts,
        boolean truncated, List<InventoryDiscrepancy> discrepancies) {}
