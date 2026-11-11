package amazon.springcloud.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class WriteRateWindowTests {
    @Test
    void countsPerPrincipalAndResetsAtNextMinute() {
        WriteRateWindow window = new WriteRateWindow(2, 5);
        Instant t = Instant.ofEpochSecond(120);
        assertTrue(window.permit("issuer|alice", t));
        assertTrue(window.permit("issuer|alice", t.plusSeconds(1)));
        assertFalse(window.permit("issuer|alice", t.plusSeconds(2)));
        assertTrue(window.permit("issuer|bob", t.plusSeconds(2)));
        assertTrue(window.permit("issuer|alice", t.plusSeconds(60)));
    }

    @Test
    void refusesUnboundedPrincipalCardinalityAndEvictsExpired() {
        WriteRateWindow window = new WriteRateWindow(1, 2);
        Instant t = Instant.ofEpochSecond(120);
        assertTrue(window.permit("a", t));
        assertTrue(window.permit("b", t));
        assertFalse(window.permit("c", t));
        assertTrue(window.permit("c", t.plusSeconds(60)));
    }

    @Test
    void rootCompositeWriteIsProtectedButWebhookAndReadsAreExcluded() {
        assertTrue(WriteRateLimitFilter.shouldLimit(HttpMethod.POST, "/product-composite"));
        assertTrue(WriteRateLimitFilter.shouldLimit(HttpMethod.POST, "/product-composite/orders"));
        assertTrue(WriteRateLimitFilter.shouldLimit(HttpMethod.PUT, "/product-composite/cart/items/10"));
        assertFalse(WriteRateLimitFilter.shouldLimit(HttpMethod.GET, "/product-composite/orders"));
        assertFalse(WriteRateLimitFilter.shouldLimit(HttpMethod.POST, "/product-composite/payments/mock-webhook"));
        assertFalse(WriteRateLimitFilter.shouldLimit(HttpMethod.POST, "/actuator/health"));
    }

    @Test
    void rejectsInvalidConfigurationAndMissingIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new WriteRateWindow(0, 2));
        assertThrows(IllegalArgumentException.class, () -> new WriteRateWindow(1, 0));
        assertFalse(new WriteRateWindow(1, 2).permit("", Instant.now()));
    }
}
