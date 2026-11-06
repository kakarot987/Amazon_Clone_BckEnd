package amazon.api.commerce;

/** Signed simulator callback. The payment ID equals the reserved order ID. */
public class MockPaymentEvent {
    private String eventId;
    private String paymentId;
    private String outcome;

    public MockPaymentEvent() {}

    public MockPaymentEvent(String eventId, String paymentId, String outcome) {
        this.eventId = eventId;
        this.paymentId = paymentId;
        this.outcome = outcome;
    }

    public String getEventId() { return eventId; }
    public String getPaymentId() { return paymentId; }
    public String getOutcome() { return outcome; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
}
