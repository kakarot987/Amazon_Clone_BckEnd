package amazon.api.event;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.ser.ZonedDateTimeSerializer;

import java.time.ZonedDateTime;

import static java.time.ZonedDateTime.now;

public class Event<K, T> {

    public enum Type {CREATE, UPDATE, DELETE}

    private Event.Type eventType;
    private K key;
    private T data;
    private ZonedDateTime eventCreatedAt;
    /** Stable identity across retries and broker redeliveries. */
    private String eventId;

    public Event() {
        this.eventType = null;
        this.key = null;
        this.data = null;
        this.eventCreatedAt = null;
        this.eventId = null; // Legacy messages may omit this field.
    }

    public Event(Type eventType, K key, T data) {
        this.eventType = eventType;
        this.key = key;
        this.data = data;
        this.eventCreatedAt = now();
        this.eventId = java.util.UUID.randomUUID().toString();
    }

    /** Construct a replay with the original ID; useful for retry/replay tests. */
    public Event(Type type, K key, T data, String eventId) {
        this(type, key, data);
        if (eventId == null || !eventId.matches("[a-zA-Z0-9._-]{8,100}"))
            throw new IllegalArgumentException("Invalid eventId");
        this.eventId = eventId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public Type getEventType() {
        return eventType;
    }

    public K getKey() {
        return key;
    }

    public T getData() {
        return data;
    }

    @JsonSerialize(using = ZonedDateTimeSerializer.class)
    public ZonedDateTime getEventCreatedAt() {
        return eventCreatedAt;
    }
}
