package amazon.microservices.core.product.services;

import amazon.api.core.product.Product;
import amazon.api.event.Event;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.function.Consumer;

/** Broker handler lets exceptions propagate for retry/DLQ processing. */
@Configuration
public class MessageProcessorConfig {
    private final ProductEventInboxHandler handler;

    public MessageProcessorConfig(ProductEventInboxHandler handler) {
        this.handler = handler;
    }

    @Bean
    public Consumer<Event<Integer, Product>> messageProcessor() {
        return handler::accept;
    }
}
