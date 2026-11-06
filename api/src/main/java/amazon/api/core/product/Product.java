package amazon.api.core.product;

import java.math.BigDecimal;
import java.util.List;

/**
 * Public product contract. New catalog fields are nullable so older event
 * payloads and products remain readable during an incremental rollout.
 */
public class Product {
    private int productId;
    private String name;
    private int weight;
    private String serviceAddress;
    private String sku;
    private String description;
    private String category;
    private BigDecimal price;
    private String currency;
    private Integer stockQuantity;
    private List<String> imageUrls;

    public Product() {}

    public Product(int productId, String name, int weight, String serviceAddress) {
        this.productId = productId;
        this.name = name;
        this.weight = weight;
        this.serviceAddress = serviceAddress;
    }

    public int getProductId() { return productId; }
    public String getName() { return name; }
    public int getWeight() { return weight; }
    public String getServiceAddress() { return serviceAddress; }
    public String getSku() { return sku; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    public Integer getStockQuantity() { return stockQuantity; }
    public List<String> getImageUrls() { return imageUrls; }

    public void setProductId(int productId) { this.productId = productId; }
    public void setName(String name) { this.name = name; }
    public void setWeight(int weight) { this.weight = weight; }
    public void setServiceAddress(String serviceAddress) { this.serviceAddress = serviceAddress; }
    public void setSku(String sku) { this.sku = sku; }
    public void setDescription(String description) { this.description = description; }
    public void setCategory(String category) { this.category = category; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setStockQuantity(Integer stockQuantity) { this.stockQuantity = stockQuantity; }
    public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }
}
