package amazon.microservices.core.product.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One Mongo document per JWT-derived opaque owner key. */
@Document(collection = "customer_wishlists")
public class WishlistEntity {
    @Id private String id;
    @Version private Long version;
    private List<WishlistEntry> entries = new ArrayList<>();
    private Instant updatedAt;

    public WishlistEntity() {}
    public WishlistEntity(String id) { this.id = id; }

    public String getId() { return id; }
    public Long getVersion() { return version; }
    public List<WishlistEntry> getEntries() { return entries; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setId(String id) { this.id = id; }
    public void setVersion(Long version) { this.version = version; }
    public void setEntries(List<WishlistEntry> entries) { this.entries = entries; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
