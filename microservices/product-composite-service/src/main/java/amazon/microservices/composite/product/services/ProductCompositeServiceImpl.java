package amazon.microservices.composite.product.services;

import amazon.api.composite.product.*;
import amazon.api.event.Event;
import amazon.microservices.composite.product.outbox.OutboundEvent;
import static amazon.api.event.Event.Type.CREATE;
import static amazon.api.event.Event.Type.DELETE;
import static java.util.logging.Level.FINE;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import amazon.microservices.composite.product.services.tracing.ObservationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import amazon.api.core.product.Product;
import amazon.api.core.recommendation.Recommendation;
import amazon.api.core.review.Review;
import amazon.util.exceptions.NotFoundException;
import amazon.util.http.ServiceUtil;

import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

@RestController
public class ProductCompositeServiceImpl implements ProductCompositeService {

    private static final Logger LOG = LoggerFactory.getLogger(ProductCompositeServiceImpl.class);

    private final SecurityContext nullSecCtx = new SecurityContextImpl();

    private final ServiceUtil serviceUtil;
    private final ObservationUtil observationUtil;
    private final ProductCompositeIntegration integration;

    @Autowired
    public ProductCompositeServiceImpl(ServiceUtil serviceUtil, ObservationUtil observationUtil, ProductCompositeIntegration integration) {
        this.serviceUtil = serviceUtil;
        this.observationUtil = observationUtil;
        this.integration = integration;
    }

    @Override
    public Mono<Void> createProduct(ProductAggregate body) {
        return observationWithProductInfo(body.getProductId(), () -> createProductInternal(body));
    }

    private Mono<Void> createProductInternal(ProductAggregate body) {
        // Save all destination-specific events in ONE MongoDB transaction.
        // HTTP 202 means durable intent, NOT downstream processing completed.
        List<OutboundEvent> messages = new ArrayList<>();
        Product product = new Product(body.getProductId(), body.getName(), body.getWeight(), null);
        product.setSku(body.getSku());
        product.setDescription(body.getDescription());
        product.setCategory(body.getCategory());
        product.setPrice(body.getPrice());
        product.setCurrency(body.getCurrency());
        product.setStockQuantity(body.getStockQuantity());
        product.setImageUrls(body.getImageUrls());
        messages.add(new OutboundEvent("products-out-0",
                new Event<>(CREATE,body.getProductId(),product)));

        if (body.getRecommendations() != null) {
            body.getRecommendations().forEach(r -> messages.add(
                    new OutboundEvent("recommendations-out-0",
                            new Event<>(CREATE,body.getProductId(),
                                    new Recommendation(body.getProductId(),r.getRecommendationId(),
                                            r.getAuthor(),r.getRate(),r.getContent(),null)))));
        }
        if (body.getReviews() != null) {
            body.getReviews().forEach(r -> messages.add(
                    new OutboundEvent("reviews-out-0",
                            new Event<>(CREATE,body.getProductId(),
                                    new Review(body.getProductId(),r.getReviewId(),r.getAuthor(),
                                            r.getSubject(),r.getContent(),null)))));
        }
        return getLogAuthorizationInfoMono()
                .then(integration.enqueueAggregate(messages))
                .doOnError(error -> LOG.warn("Could not persist product aggregate event batch: {}",
                        error.getClass().getSimpleName()));
    }

    @Override
    public Mono<ProductAggregate> getProduct(int productId, int delay, int faultPercent) {
        return observationWithProductInfo(productId, () -> getProductInternal(productId, delay, faultPercent));
    }

    private Mono<ProductAggregate> getProductInternal(int productId, int delay, int faultPercent) {
        LOG.info("Will get composite product info for product.id={}", productId);
        return Mono.zip(
                        values -> createProductAggregate(
                                (SecurityContext) values[0], (Product) values[1], (List<Recommendation>) values[2], (List<Review>) values[3], serviceUtil.getServiceAddress()),
                        getSecurityContextMono(),
                        integration.getProduct(productId, delay, faultPercent),
                        integration.getRecommendations(productId).collectList(),
                        integration.getReviews(productId).collectList())
                .doOnError(ex -> LOG.warn("getCompositeProduct failed: {}", ex.toString()))
                .log(LOG.getName(), FINE);
    }

    @Override
    public Mono<Void> deleteProduct(int productId) {
        return observationWithProductInfo(productId, () -> deleteProductInternal(productId));
    }

    private Mono<Void> deleteProductInternal(int productId) {
        List<OutboundEvent> messages = List.of(
                new OutboundEvent("products-out-0",new Event<>(DELETE,productId,null)),
                new OutboundEvent("recommendations-out-0",new Event<>(DELETE,productId,null)),
                new OutboundEvent("reviews-out-0",new Event<>(DELETE,productId,null)));
        return getLogAuthorizationInfoMono()
                .then(integration.enqueueAggregate(messages))
                .doOnError(error -> LOG.warn("Could not persist delete batch: {}",
                        error.getClass().getSimpleName()));
    }

    private <T> T observationWithProductInfo(int productInfo, Supplier<T> supplier) {
        return observationUtil.observe(
                "composite observation",
                "product info",
                "productId",
                String.valueOf(productInfo),
                supplier);
    }

    private ProductAggregate createProductAggregate(
            SecurityContext sc, Product product, List<Recommendation> recommendations, List<Review> reviews, String serviceAddress) {

        logAuthorizationInfo(sc);

        // 1. Setup product info
        int productId = product.getProductId();
        String name = product.getName();
        int weight = product.getWeight();

        // 2. Copy summary recommendation info, if available
        List<RecommendationSummary> recommendationSummaries = (recommendations == null) ? null :
                recommendations.stream()
                        .map(r -> new RecommendationSummary(r.getRecommendationId(), r.getAuthor(), r.getRate(), r.getContent()))
                        .collect(Collectors.toList());

        // 3. Copy summary review info, if available
        List<ReviewSummary> reviewSummaries = (reviews == null)  ? null :
                reviews.stream()
                        .map(r -> new ReviewSummary(r.getReviewId(), r.getAuthor(), r.getSubject(), r.getContent()))
                        .collect(Collectors.toList());

        // 4. Create info regarding the involved microservices addresses
        String productAddress = product.getServiceAddress();
        String reviewAddress = (reviews != null && reviews.size() > 0) ? reviews.get(0).getServiceAddress() : "";
        String recommendationAddress = (recommendations != null && recommendations.size() > 0) ? recommendations.get(0).getServiceAddress() : "";
        ServiceAddresses serviceAddresses = new ServiceAddresses(serviceAddress, productAddress, reviewAddress, recommendationAddress);

        ProductAggregate aggregate = new ProductAggregate(productId, name, weight, recommendationSummaries, reviewSummaries, serviceAddresses);
        aggregate.setSku(product.getSku());
        aggregate.setDescription(product.getDescription());
        aggregate.setCategory(product.getCategory());
        aggregate.setPrice(product.getPrice());
        aggregate.setCurrency(product.getCurrency());
        aggregate.setStockQuantity(product.getStockQuantity());
        aggregate.setImageUrls(product.getImageUrls());
        return aggregate;
    }

    private Mono<SecurityContext> getLogAuthorizationInfoMono() {
        return getSecurityContextMono().doOnNext(sc -> logAuthorizationInfo(sc));
    }

    private Mono<SecurityContext> getSecurityContextMono() {
        return ReactiveSecurityContextHolder.getContext().defaultIfEmpty(nullSecCtx);
    }

    private void logAuthorizationInfo(SecurityContext sc) {
        if (sc != null && sc.getAuthentication() != null && sc.getAuthentication() instanceof JwtAuthenticationToken) {
            Jwt jwtToken = ((JwtAuthenticationToken)sc.getAuthentication()).getToken();
            logAuthorizationInfo(jwtToken);
        } else {
            LOG.warn("No JWT based Authentication supplied, running tests are we?");
        }
    }

    private void logAuthorizationInfo(Jwt jwt) {
        if (jwt == null) {
            LOG.warn("No JWT supplied, running tests are we?");
        } else {
            if (LOG.isDebugEnabled()) {
                URL issuer = jwt.getIssuer();
                List<String> audience = jwt.getAudience();
                Object subject = jwt.getClaims().get("sub");
                Object scopes = jwt.getClaims().get("scope");
                Object expires = jwt.getClaims().get("exp");

                LOG.debug("Authorization info: Subject: {}, scopes: {}, expires {}: issuer: {}, audience: {}", subject, scopes, expires, issuer, audience);
            }
        }
    }
}