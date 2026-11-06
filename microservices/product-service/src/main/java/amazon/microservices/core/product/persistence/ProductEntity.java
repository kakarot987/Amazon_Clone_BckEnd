package amazon.microservices.core.product.persistence;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import static java.lang.String.format;

@Document(collection="products")
public class ProductEntity {

    @Id
    private String id;

    @Version
    private Integer version;

    @Indexed(unique = true)
    private int productId;

    private String name;
    private int weight;
    private String sku;
    private String description;
    private String category;
    private BigDecimal price;
    private String currency;
    private Integer stockQuantity;
    // Reserving tokens live alongside stock for a single-document atomic update.
    private List<String> reservedOrderIds;
    private List<String> imageUrls;

    public ProductEntity() {
    }

    public ProductEntity(int productId, String name, int weight) {
        this.productId = productId;
        this.name = name;
        this.weight = weight;
    }

    @Override
    public String toString() {
        return format("ProductEntity: %s", productId);
    }

    public String getId() {
        return id;
    }

    public Integer getVersion() {
        return version;
    }

    public int getProductId() {
        return productId;
    }

    public String getName() {
        return name;
    }

    public int getWeight() {
        return weight;
    }

    public String getSku() { return sku; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    public Integer getStockQuantity() { return stockQuantity; }
    public List<String> getImageUrls() { return imageUrls; }
    public List<String> getReservedOrderIds() { return reservedOrderIds; }
    public void setReservedOrderIds(List<String> reservedOrderIds) { this.reservedOrderIds = reservedOrderIds; }
    public void setSku(String sku) { this.sku = sku; }
    public void setDescription(String description) { this.description = description; }
    public void setCategory(String category) { this.category = category; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setStockQuantity(Integer stockQuantity) { this.stockQuantity = stockQuantity; }
    public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }

    public void setId(String id) {
        this.id = id;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public void setProductId(int productId) {
        this.productId = productId;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }
}
