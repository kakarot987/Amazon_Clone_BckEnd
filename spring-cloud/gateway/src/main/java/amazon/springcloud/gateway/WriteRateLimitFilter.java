package amazon.springcloud.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Per-authenticated-subject write throttle for local/single-gateway demos.
 * Downstream API scopes remain authoritative. No raw IP/X-Forwarded-For trust.
 */
@Component
@Order(-50)
public class WriteRateLimitFilter implements WebFilter {
    private final WriteRateWindow limiter;

    public WriteRateLimitFilter(
            @Value("${app.gateway.write-rate-limit.per-minute:60}") int limit,
            @Value("${app.gateway.write-rate-limit.max-callers:10000}") int maxCallers) {
        this.limiter = new WriteRateWindow(limit, maxCallers);
    }

    static boolean shouldLimit(HttpMethod method, String path) {
        return (path.equals("/product-composite") || path.startsWith("/product-composite/"))
                && !path.equals("/product-composite/payments/mock-webhook")
                && (HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method)
                    || HttpMethod.PATCH.equals(method) || HttpMethod.DELETE.equals(method));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        String path = exchange.getRequest().getPath().value();
        if (!shouldLimit(method, path)) {
            return chain.filter(exchange);
        }
        return exchange.getPrincipal()
                .filter(principal -> principal instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .map(authentication -> {
                    var jwt = authentication.getToken();
                    return jwt.getIssuer() != null && jwt.getSubject() != null
                            && limiter.permit(jwt.getIssuer() + "|" + jwt.getSubject(), Instant.now());
                })
                .defaultIfEmpty(true)
                .flatMap(allowed -> {
                    if (!allowed) {
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        exchange.getResponse().getHeaders().set("Retry-After", "60");
                        return exchange.getResponse().setComplete();
                    }
                    return chain.filter(exchange);
                });
    }
}
