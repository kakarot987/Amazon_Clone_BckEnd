package amazon.api.discovery;

import java.util.List;

public record ReviewReportPage(List<ReviewReportReceipt> reports, int page, int size,
                               long totalElements, int totalPages) {}
