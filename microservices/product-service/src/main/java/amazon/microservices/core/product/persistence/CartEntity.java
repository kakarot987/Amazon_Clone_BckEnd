package amazon.microservices.core.product.persistence;

import amazon.api.commerce.CartItem;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "shopping_carts")
public class CartEntity {
    @Id
    private String id;

    @Version
    private Long version;

    private List<CartItem> items = new ArrayList<>();
    private Instant updatedAt;

    public CartEntity() {}

    public CartEntity(String id) { this.id = id; }

    public String getId() { return id; }
    public Long getVersion() { return version; }
    public List<CartItem> getItems() { return items; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setId(String id) { this.id = id; }
    public void setVersion(Long version) { this.version = version; }
    public void setItems(List<CartItem> items) { this.items = items; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
