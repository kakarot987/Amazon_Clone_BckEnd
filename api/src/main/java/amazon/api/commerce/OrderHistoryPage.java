package amazon.api.commerce;

import java.util.List;

public record OrderHistoryPage(List<OrderHistoryItem> orders, int page, int size,
                               long totalElements, long totalPages) {}
