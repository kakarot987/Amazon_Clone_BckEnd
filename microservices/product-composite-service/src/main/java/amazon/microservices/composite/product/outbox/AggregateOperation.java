package amazon.microservices.composite.product.outbox;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable-intent journal for one atomic outbox submission.
 * Records intended events only; does not claim consumer acknowledgement.
 */
@Document("composite_operations")
@CompoundIndex(name="operation_by_product_idx", def="{'productIds':1,'createdAt':-1}")
public class AggregateOperation {
    @Id private String id;
    private Instant createdAt;
    private List<Integer> productIds;
    private List<Step> steps;

    public AggregateOperation() {}

    public AggregateOperation(String id, Instant createdAt,
                              List<Integer> productIds, List<Step> steps) {
        this.id = id;
        this.createdAt = createdAt;
        this.productIds = List.copyOf(productIds);
        this.steps = List.copyOf(steps);
    }

    public String getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public List<Integer> getProductIds() { return productIds; }
    public List<Step> getSteps() { return steps; }
    public void setId(String id) { this.id = id; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setProductIds(List<Integer> productIds) { this.productIds = productIds; }
    public void setSteps(List<Step> steps) { this.steps = steps; }

    /** No payloads or customer data: metadata only for operator correlation. */
    public static class Step {
        private String eventId;
        private int productId;
        private String bindingName;
        private String eventType;

        public Step() {}
        public Step(String eventId, int productId, String bindingName, String eventType) {
            this.eventId = eventId;
            this.productId = productId;
            this.bindingName = bindingName;
            this.eventType = eventType;
        }
        public String getEventId() { return eventId; }
        public int getProductId() { return productId; }
        public String getBindingName() { return bindingName; }
        public String getEventType() { return eventType; }
        public void setEventId(String eventId) { this.eventId = eventId; }
        public void setProductId(int productId) { this.productId = productId; }
        public void setBindingName(String bindingName) { this.bindingName = bindingName; }
        public void setEventType(String eventType) { this.eventType = eventType; }
    }
}
