package amazon.springcloud.gateway;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded, local-only fixed-window throttle. It is not a distributed
 * rate limiter and must not be used for billing or abuse-prevention guarantees.
 */
final class WriteRateWindow {
    private record Counter(long minute, int requests) {}

    private final ConcurrentHashMap<String, Counter> callers = new ConcurrentHashMap<>();
    private final int perMinute;
    private final int maxCallers;

    WriteRateWindow(int perMinute, int maxCallers) {
        if (perMinute < 1 || perMinute > 10_000 || maxCallers < 1)
            throw new IllegalArgumentException("Invalid local write throttle configuration");
        this.perMinute = perMinute;
        this.maxCallers = maxCallers;
    }

    boolean permit(String identity, Instant now) {
        if (identity == null || identity.isBlank() || now == null)
            return false;
        long minute = now.getEpochSecond() / 60;
        // Reclaim expired entries to prevent unlimited memory growth.
        if (callers.size() >= maxCallers) {
            callers.entrySet().removeIf(entry -> entry.getValue().minute() < minute);
        }
        if (!callers.containsKey(identity) && callers.size() >= maxCallers)
            return false;
        Counter updated = callers.compute(identity, (key, old) ->
                old == null || old.minute() != minute
                        ? new Counter(minute, 1)
                        : new Counter(minute, Math.min(perMinute + 1, old.requests() + 1)));
        return updated.requests() <= perMinute;
    }
}
