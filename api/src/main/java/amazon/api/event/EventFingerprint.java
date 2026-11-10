package amazon.api.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable fingerprint of the business operation (excludes event creation time).
 * Event ID alone is insufficient: reject replay with changed type/key/payload.
 * No user information is exposed in the fingerprint or in logs.
 */
public final class EventFingerprint {
    private EventFingerprint() {}

    public static String of(Event<?, ?> event, ObjectMapper mapper) {
        try {
            ObjectMapper canonical = mapper.copy()
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            byte[] body = canonical.writeValueAsBytes(
                    new Operation(event.getEventType(), event.getKey(), event.getData()));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalArgumentException("Unable to fingerprint incoming event");
        }
    }

    private record Operation(Event.Type eventType, Object key, Object data) {}

    public static String checkedId(Event<?, ?> event) {
        String id = event.getEventId();
        if (id == null) return null; // Old broker messages continue through legacy handling.
        if (!id.matches("[a-zA-Z0-9._-]{8,100}"))
            throw new IllegalArgumentException("Invalid event ID");
        return id;
    }
}
