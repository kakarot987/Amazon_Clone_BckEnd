package amazon.springcloud.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;

@Configuration
public class HealthCheckConfiguration implements HealthIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(HealthCheckConfiguration.class);

    private final WebClient.Builder webClientBuilder;

    private WebClient webClient;

    @Autowired
    public HealthCheckConfiguration(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();

        checkHealth("http://auth-server").subscribe(response -> {
            if (!response) {
                builder.down().withDetail("auth-server", "Down");
            }
        });

        checkHealth("http://product").subscribe(response -> {
            if (!response) {
                builder.down().withDetail("product", "Down");
            }
        });

        checkHealth("http://recommendation").subscribe(response -> {
            if (!response) {
                builder.down().withDetail("recommendation", "Down");
            }
        });

        checkHealth("http://review").subscribe(response -> {
            if (!response) {
                builder.down().withDetail("review", "Down");
            }
        });

        checkHealth("http://product-composite").subscribe(response -> {
            if (!response) {
                builder.down().withDetail("product-composite", "Down");
            }
        });

        return builder.build();
    }

    private Mono<Boolean> checkHealth(String url) {
        return getWebClient().get().uri(URI.create(url)).exchangeToMono(response -> Mono.just(response.statusCode().is2xxSuccessful()));
    }

    private WebClient getWebClient() {
        if (webClient == null) {
            webClient = webClientBuilder.build();
        }
        return webClient;
    }
}
