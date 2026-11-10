package amazon.microservices.core.review.services;

import amazon.api.core.review.Review;
import amazon.api.event.Event;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.function.Consumer;

/** Separate transactional Spring bean keeps JPA inbox + mutation atomic. */
@Configuration
public class MessageProcessorConfig {
    private final ReviewEventInboxHandler handler;

    public MessageProcessorConfig(ReviewEventInboxHandler handler) {
        this.handler = handler;
    }

    @Bean
    public Consumer<Event<Integer, Review>> messageProcessor() {
        return handler::accept;
    }
}
