package amazon.api.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventFingerprintTests {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void envelopeIdSurvivesJsonRoundTripAndTimestampDoesNotChangeBusinessFingerprint() throws Exception {
        Event<Integer, String> first = new Event<>(Event.Type.CREATE, 42, "value", "event-roundtrip-42");
        String json = mapper.writeValueAsString(first);
        assertTrue(json.contains("\"eventId\":\"event-roundtrip-42\""));
        var second = mapper.readValue(json, Event.class);
        assertEquals("event-roundtrip-42", second.getEventId());
        assertEquals(EventFingerprint.of(first, mapper), EventFingerprint.of(second, mapper));
    }

    @Test
    void fingerprintDetectsDifferentPayloadAndValidatesIds() {
        Event<Integer, String> first = new Event<>(Event.Type.UPDATE, 41, "value", "event-change-41");
        Event<Integer, String> changed = new Event<>(Event.Type.UPDATE, 41, "changed", "event-change-41");
        assertNotEquals(EventFingerprint.of(first, mapper), EventFingerprint.of(changed, mapper));

        Event<Integer, String> legacy = new Event<>();
        legacy.setEventId(null);
        assertNull(EventFingerprint.checkedId(legacy));
        legacy.setEventId("invalid id with spaces");
        assertThrows(IllegalArgumentException.class, () -> EventFingerprint.checkedId(legacy));
    }
}
