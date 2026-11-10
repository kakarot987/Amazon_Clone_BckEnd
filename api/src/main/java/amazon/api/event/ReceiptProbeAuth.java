package amazon.api.event;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

/** Short-lived HMAC read-only internal receipt probes. */
public final class ReceiptProbeAuth {
    private static final Set<String> BINDINGS = Set.of(
            "products-out-0", "recommendations-out-0", "reviews-out-0");
    private static final long WINDOW_SECONDS = 60;

    private ReceiptProbeAuth() {}

    public static boolean configured(String secret) {
        return secret != null && secret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    public static String sign(String secret, String destination, String eventId, long epochSeconds) {
        if (!configured(secret))
            throw new IllegalArgumentException("Receipt probe secret must be at least 32 bytes");
        if (!valid(destination, eventId))
            throw new IllegalArgumentException("Invalid receipt destination or event ID");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String message = "GET\n" + destination + "\n" + eventId + "\n" + epochSeconds;
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    public static boolean verify(String secret, String destination, String eventId,
                                 String timestamp, String signature, Instant now) {
        if (!configured(secret) || !valid(destination, eventId)
                || timestamp == null || !timestamp.matches("[0-9]{10,12}")
                || signature == null || !signature.matches("[0-9a-f]{64}") || now == null)
            return false;
        try {
            long epoch = Long.parseLong(timestamp);
            long current = now.getEpochSecond();
            if (epoch < current - WINDOW_SECONDS || epoch > current + WINDOW_SECONDS)
                return false;
            byte[] actual = HexFormat.of().parseHex(signature);
            byte[] expected = HexFormat.of().parseHex(sign(secret, destination, eventId, epoch));
            return MessageDigest.isEqual(actual, expected);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean valid(String binding, String id) {
        return BINDINGS.contains(binding) && id != null
                && id.matches("[a-zA-Z0-9._-]{8,100}");
    }
}
