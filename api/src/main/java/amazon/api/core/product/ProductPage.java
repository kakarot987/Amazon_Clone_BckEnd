package amazon.api.core.product;

import java.util.List;

/** A stable, bounded page of catalog results with count metadata. */
public class ProductPage {
    private List<Product> products;
    private int page;
    private int size;
    private long totalElements;
    private long totalPages;

    public ProductPage() {}

    public ProductPage(List<Product> products, int page, int size, long totalElements) {
        this.products = products;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalElements == 0 ? 0 : 1 + (totalElements - 1) / size;
    }

    public List<Product> getProducts() { return products; }
    public int getPage() { return page; }
    public int getSize() { return size; }
    public long getTotalElements() { return totalElements; }
    public long getTotalPages() { return totalPages; }

    public void setProducts(List<Product> products) { this.products = products; }
    public void setPage(int page) { this.page = page; }
    public void setSize(int size) { this.size = size; }
    public void setTotalElements(long totalElements) { this.totalElements = totalElements; }
    public void setTotalPages(long totalPages) { this.totalPages = totalPages; }
}
