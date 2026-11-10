package amazon.microservices.core.product.persistence;

import amazon.api.commerce.ShipmentEvent;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One shipment per order; @Version protects concurrent event transitions. */
@Document(collection = "simulated_shipments")
public class ShipmentEntity {
    @Id private String id;
    @Version private Long version;
    private String orderId;
    private String status;
    private String trackingNumber;
    private String destinationZone;
    private Instant createdAt;
    private Instant updatedAt;
    private List<ShipmentEvent> history = new ArrayList<>();

    public ShipmentEntity() {}
    public ShipmentEntity(String orderId, String destinationZone, String trackingNumber) {
        this.id = orderId;
        this.orderId = orderId;
        this.destinationZone = destinationZone;
        this.trackingNumber = trackingNumber;
        this.status = "CREATED";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.history = new ArrayList<>(List.of(new ShipmentEvent(
                "shipment-created", "CREATED", createdAt)));
    }

    public String getId() { return id; }
    public Long getVersion() { return version; }
    public String getOrderId() { return orderId; }
    public String getStatus() { return status; }
    public String getTrackingNumber() { return trackingNumber; }
    public String getDestinationZone() { return destinationZone; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<ShipmentEvent> getHistory() { return history; }

    public void setId(String id) { this.id = id; }
    public void setVersion(Long version) { this.version = version; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public void setStatus(String status) { this.status = status; }
    public void setTrackingNumber(String trackingNumber) { this.trackingNumber = trackingNumber; }
    public void setDestinationZone(String destinationZone) { this.destinationZone = destinationZone; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setHistory(List<ShipmentEvent> history) { this.history = history; }
}
