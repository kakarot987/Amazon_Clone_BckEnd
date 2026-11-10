package amazon.microservices.composite.product.services;

import amazon.api.discovery.ModerationReviewDetails;
import amazon.api.discovery.ReviewModerationRequest;
import amazon.api.discovery.ReviewReportPage;
import amazon.api.discovery.ReviewReportReceipt;
import amazon.api.discovery.ReviewReportRequest;
import amazon.util.exceptions.InvalidInputException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Customer reports and privileged review moderation with separate scopes. */
@RestController
@RequestMapping("/product-composite")
public class ReviewModerationCompositeController {
    private final WebClient client;
    public ReviewModerationCompositeController(WebClient client) { this.client = client; }

    @PostMapping(value = "/products/{productId}/reviews/{reviewId}/report",
            consumes = "application/json", produces = "application/json")
    public Mono<ReviewReportReceipt> report(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable int productId, @PathVariable Long reviewId,
                                             @RequestBody ReviewReportRequest request) {
        String owner = CommerceCompositeController.ownerKey(jwt);
        if (productId < 1 || reviewId == null || reviewId < 1)
            throw new InvalidInputException("Invalid review");
        return client.post().uri(
                        "http://review/internal/review-moderation/products/{id}/reviews/{review}/reports/owner/{owner}",
                        productId, reviewId, owner)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .retrieve().bodyToMono(ReviewReportReceipt.class).onErrorMap(this::mapError);
    }

    @GetMapping(value = "/moderation/reports", produces = "application/json")
    public Mono<ReviewReportPage> reports(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return client.get().uri(builder -> builder.scheme("http").host("review")
                        .path("/internal/review-moderation/reports")
                        .queryParam("page", page).queryParam("size", size).build())
                .retrieve().bodyToMono(ReviewReportPage.class).onErrorMap(this::mapError);
    }

    @GetMapping(value = "/moderation/products/{productId}/reviews/{reviewId}",
            produces = "application/json")
    public Mono<ModerationReviewDetails> detail(@PathVariable int productId,
                                                 @PathVariable Long reviewId) {
        return client.get().uri(
                        "http://review/internal/review-moderation/products/{id}/reviews/{review}",
                        productId, reviewId)
                .retrieve().bodyToMono(ModerationReviewDetails.class).onErrorMap(this::mapError);
    }

    @PostMapping(value = "/moderation/products/{productId}/reviews/{reviewId}/decision",
            consumes = "application/json", produces = "application/json")
    public Mono<ModerationReviewDetails> decide(@PathVariable int productId, @PathVariable Long reviewId,
                                                 @RequestBody ReviewModerationRequest request) {
        return client.post().uri(
                        "http://review/internal/review-moderation/products/{id}/reviews/{review}/decision",
                        productId, reviewId)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .retrieve().bodyToMono(ModerationReviewDetails.class).onErrorMap(this::mapError);
    }

    private Throwable mapError(Throwable ex) {
        if (ex instanceof WebClientResponseException response) {
            HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
            if (status != null) return new ResponseStatusException(status, "Review moderation operation rejected");
        }
        return ex;
    }
}
