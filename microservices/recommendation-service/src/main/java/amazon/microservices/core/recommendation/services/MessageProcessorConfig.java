package amazon.microservices.core.recommendation.services;

import amazon.api.core.recommendation.Recommendation;
import amazon.api.event.Event;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.function.Consumer;

/** Throw on failure so RabbitMQ binder retry/DLQ can handle redelivery. */
@Configuration
public class MessageProcessorConfig {
    private final RecommendationEventInboxHandler handler;

    public MessageProcessorConfig(RecommendationEventInboxHandler handler) {
        this.handler = handler;
    }

    @Bean
    public Consumer<Event<Integer, Recommendation>> messageProcessor() {
        return handler::accept;
    }
}
