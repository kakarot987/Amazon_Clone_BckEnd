package amazon.api.event;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/** Internal receipt-probe authentication rejects wrong bindings, stale timestamps and signatures. */
class ReceiptProbeAuthTests {
    private static final String SECRET = "test-only-unique-32-byte-probe-secret-never-deploy";
    private static final String EVENT = "event-01234567";
    private static final String BINDING = "products-out-0";

    @Test
    void roundTripAndReplayWindow() {
        long now = 1783770000L;
        String signature = ReceiptProbeAuth.sign(SECRET, BINDING, EVENT, now);
        assertTrue(ReceiptProbeAuth.verify(SECRET, BINDING, EVENT,
                Long.toString(now), signature, Instant.ofEpochSecond(now)));
        assertFalse(ReceiptProbeAuth.verify(SECRET, BINDING, EVENT,
                Long.toString(now), signature, Instant.ofEpochSecond(now + 61)));
        assertFalse(ReceiptProbeAuth.verify(SECRET, BINDING, EVENT,
                Long.toString(now), signature, Instant.ofEpochSecond(now - 61)));
    }

    @Test
    void rejectsWrongDestinationAndTampering() {
        long now = 1783770000L;
        String signature = ReceiptProbeAuth.sign(SECRET, BINDING, EVENT, now);
        assertFalse(ReceiptProbeAuth.verify(SECRET, "reviews-out-0", EVENT,
                Long.toString(now), signature, Instant.ofEpochSecond(now)));
        assertFalse(ReceiptProbeAuth.verify(SECRET, BINDING, "event-98765432",
                Long.toString(now), signature, Instant.ofEpochSecond(now)));
        assertFalse(ReceiptProbeAuth.verify(SECRET, BINDING, EVENT,
                Long.toString(now), "invalid", Instant.ofEpochSecond(now)));
        assertFalse(ReceiptProbeAuth.verify(SECRET, BINDING, EVENT,
                "not-a-timestamp", signature, Instant.ofEpochSecond(now)));
    }

    @Test
    void cannotProbeWhenUnconfigured() {
        assertFalse(ReceiptProbeAuth.configured(""));
        assertFalse(ReceiptProbeAuth.verify("", BINDING, EVENT,
                "1783770000", "a".repeat(64), Instant.ofEpochSecond(1783770000L)));
        assertThrows(IllegalArgumentException.class,
                () -> ReceiptProbeAuth.sign("weak", BINDING, EVENT, 1783770000L));
        assertThrows(IllegalArgumentException.class,
                () -> ReceiptProbeAuth.sign(SECRET, "unknown-binding", EVENT, 1783770000L));
    }
}
