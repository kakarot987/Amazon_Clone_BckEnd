package amazon.microservices.composite.product;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.server.SecurityWebFilterChain;

import static org.springframework.http.HttpMethod.*;

@TestConfiguration
public class TestSecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {

        return http.authorizeExchange(auth -> auth.anyExchange().permitAll()).csrf(cs -> cs.disable()).build();
      /*  http.csrf().disable().authorizeExchange().anyExchange().permitAll();
        return http.build();*/
    }
}

