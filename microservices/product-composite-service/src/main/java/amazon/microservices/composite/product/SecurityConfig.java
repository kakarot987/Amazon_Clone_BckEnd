
package amazon.microservices.composite.product;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.server.SecurityWebFilterChain;

import static org.springframework.http.HttpMethod.*;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {
	@Bean
	SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
		return http
				.authorizeExchange(auth -> auth.pathMatchers("/openapi/**").permitAll()
						.pathMatchers("/webjars/**").permitAll()
						.pathMatchers("/actuator/**").permitAll()
                        .pathMatchers(POST, "/product-composite/payments/mock-webhook").permitAll()
                        .pathMatchers(POST, "/product-composite/payments/orders/*/intent")
                            .hasAuthority("SCOPE_order:write")
						.pathMatchers(POST, "/product-composite/orders").hasAuthority("SCOPE_order:write")
                        .pathMatchers(POST, "/product-composite/orders/*/cancel").hasAuthority("SCOPE_order:write")
                        .pathMatchers(GET, "/product-composite/orders/*").hasAuthority("SCOPE_order:read")
                        .pathMatchers(GET, "/product-composite/cart").hasAuthority("SCOPE_cart:read")
                        .pathMatchers(PUT, "/product-composite/cart/items/**").hasAuthority("SCOPE_cart:write")
                        .pathMatchers(DELETE, "/product-composite/cart/items/**").hasAuthority("SCOPE_cart:write")
                        .pathMatchers(GET, "/product-composite/inventory/**").hasAuthority("SCOPE_inventory:read")
                        .pathMatchers(POST, "/product-composite/inventory/**").hasAuthority("SCOPE_inventory:write")
                        .pathMatchers(POST, "/product-composite/**").hasAuthority("SCOPE_product:write")
						.pathMatchers(DELETE, "/product-composite/**").hasAuthority("SCOPE_product:write")
                        .pathMatchers(PUT, "/product-composite/**").hasAuthority("SCOPE_product:write")
						.pathMatchers(GET, "/product-composite/**").hasAuthority("SCOPE_product:read")
						.anyExchange().authenticated()
				)
				.oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
				.build();
	}
}