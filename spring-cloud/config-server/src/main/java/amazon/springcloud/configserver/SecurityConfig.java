package amazon.springcloud.configserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig{
    @Value("${spring.cloud.config.server.git.uri}")
    String cloudLocation;

    @Bean
    public SecurityFilterChain configure(HttpSecurity http) throws Exception {
        System.out.println(cloudLocation + "-------------------------------" );
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll())
                .httpBasic(ht -> ht.disable())
                .build();
    }
}


