package amazon.api.composite.product;

import java.math.BigDecimal;
import java.util.List;

/** A product with optional reviews and recommendations. */
public class ProductAggregate {
    private int productId;
    private String name;
    private int weight;
    private List<RecommendationSummary> recommendations;
    private List<ReviewSummary> reviews;
    private ServiceAddresses serviceAddresses;

    private String sku;
    private String description;
    private String category;
    private BigDecimal price;
    private String currency;
    private Integer stockQuantity;
    private List<String> imageUrls;

    public ProductAggregate() {}

    // Keep the existing constructor compatible with callers and tests.
    public ProductAggregate(int productId, String name, int weight,
            List<RecommendationSummary> recommendations, List<ReviewSummary> reviews,
            ServiceAddresses serviceAddresses) {
        this.productId = productId;
        this.name = name;
        this.weight = weight;
        this.recommendations = recommendations;
        this.reviews = reviews;
        this.serviceAddresses = serviceAddresses;
    }

    public int getProductId() { return productId; }
    public String getName() { return name; }
    public int getWeight() { return weight; }
    public List<RecommendationSummary> getRecommendations() { return recommendations; }
    public List<ReviewSummary> getReviews() { return reviews; }
    public ServiceAddresses getServiceAddresses() { return serviceAddresses; }
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
    public void setRecommendations(List<RecommendationSummary> recommendations) { this.recommendations = recommendations; }
    public void setReviews(List<ReviewSummary> reviews) { this.reviews = reviews; }
    public void setServiceAddresses(ServiceAddresses serviceAddresses) { this.serviceAddresses = serviceAddresses; }
    public void setSku(String sku) { this.sku = sku; }
    public void setDescription(String description) { this.description = description; }
    public void setCategory(String category) { this.category = category; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setStockQuantity(Integer stockQuantity) { this.stockQuantity = stockQuantity; }
    public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }
}
