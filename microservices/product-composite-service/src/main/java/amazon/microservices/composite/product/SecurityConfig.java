
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
                        .pathMatchers(POST, "/product-composite/products/*/reviews/*/report").hasAuthority("SCOPE_review:report")
                        .pathMatchers(GET, "/product-composite/moderation/reports").hasAuthority("SCOPE_review:moderate")
                        .pathMatchers(GET, "/product-composite/moderation/products/*/reviews/*").hasAuthority("SCOPE_review:moderate")
                        .pathMatchers(POST, "/product-composite/moderation/products/*/reviews/*/decision").hasAuthority("SCOPE_review:moderate")
                        .pathMatchers(GET, "/product-composite/products/*/reviews").hasAuthority("SCOPE_review:read")
                        .pathMatchers(GET, "/product-composite/products/*/ratings").hasAuthority("SCOPE_review:read")
                        .pathMatchers(PUT, "/product-composite/products/*/my-review").hasAuthority("SCOPE_review:write")
                        .pathMatchers(DELETE, "/product-composite/products/*/my-review").hasAuthority("SCOPE_review:write")
                        .pathMatchers(GET, "/product-composite/products/*/related").hasAuthority("SCOPE_product:read")
                        .pathMatchers(GET, "/product-composite/wishlist").hasAuthority("SCOPE_wishlist:read")
                        .pathMatchers(PUT, "/product-composite/wishlist/items/*").hasAuthority("SCOPE_wishlist:write")
                        .pathMatchers(DELETE, "/product-composite/wishlist/items/*").hasAuthority("SCOPE_wishlist:write")
                        .pathMatchers(DELETE, "/product-composite/wishlist").hasAuthority("SCOPE_wishlist:write")
                        .pathMatchers(POST, "/product-composite/orders/*/return").hasAuthority("SCOPE_return:write")
                        .pathMatchers(GET, "/product-composite/orders/*/return").hasAuthority("SCOPE_return:read")
                        .pathMatchers(GET, "/product-composite/returns/*").hasAuthority("SCOPE_returns:read")
                        .pathMatchers(POST, "/product-composite/returns/*/*").hasAuthority("SCOPE_returns:manage")
                        .pathMatchers(GET, "/product-composite/orders/*/shipment").hasAuthority("SCOPE_order:read")
                        .pathMatchers(POST, "/product-composite/fulfillment/shipments").hasAuthority("SCOPE_fulfillment:write")
                        .pathMatchers(GET, "/product-composite/fulfillment/shipments/*").hasAuthority("SCOPE_fulfillment:read")
                        .pathMatchers(POST, "/product-composite/fulfillment/shipments/*/events").hasAuthority("SCOPE_fulfillment:write")
                        .pathMatchers(POST, "/product-composite/payments/orders/*/intent")
                            .hasAuthority("SCOPE_order:write")
						.pathMatchers(POST, "/product-composite/orders").hasAuthority("SCOPE_order:write")
                        .pathMatchers(POST, "/product-composite/orders/*/cancel").hasAuthority("SCOPE_order:write")
                        .pathMatchers(GET, "/product-composite/orders/history").hasAuthority("SCOPE_order:read")
                        .pathMatchers(GET, "/product-composite/orders/*/timeline").hasAuthority("SCOPE_order:read")
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