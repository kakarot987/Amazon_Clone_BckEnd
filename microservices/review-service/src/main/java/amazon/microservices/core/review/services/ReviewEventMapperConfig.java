package amazon.microservices.core.review.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Independent mapper fallback for JPA-only tests; no circular consumer wiring. */
@Configuration(proxyBeanMethods = false)
public class ReviewEventMapperConfig {
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper eventFingerprintMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
