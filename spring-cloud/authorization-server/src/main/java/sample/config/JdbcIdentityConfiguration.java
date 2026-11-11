package sample.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

/**
 * Opt-in persistent users and OAuth2 registered clients.
 * Flyway validates schema before seeding; persisted secrets are never silently rotated.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "auth.identity", name = "store", havingValue = "jdbc")
public class JdbcIdentityConfiguration {
    private static final List<String> CLIENT_IDS = List.of(
            "writer", "reader", "mock-fulfillment", "mock-returns", "mock-review-moderator");

    @Bean(destroyMethod = "close")
    public HikariDataSource identityDataSource(
            @Value("${AUTH_DB_URL:}") String url,
            @Value("${AUTH_DB_USERNAME:}") String username,
            @Value("${AUTH_DB_PASSWORD:}") String password) {
        if (url == null || !(url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:h2:"))
                || username == null || username.isBlank()
                || password == null || password.isBlank()) {
            throw new IllegalStateException("JDBC identity mode requires a supported URL and database credentials");
        }
        HikariConfig settings = new HikariConfig();
        settings.setJdbcUrl(url);
        settings.setUsername(username);
        settings.setPassword(password);
        settings.setPoolName("identity-db");
        settings.setMaximumPoolSize(5);
        settings.setMinimumIdle(1);
        HikariDataSource ds = new HikariDataSource(settings);
        try {
            Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration/auth")
                    .validateMigrationNaming(true)
                    .load()
                    .migrate();
            return ds;
        } catch (RuntimeException failure) {
            ds.close();
            throw new IllegalStateException("Identity database migrations failed; no demo fallback", failure);
        }
    }

    @Bean
    public UserDetailsService jdbcUsers(DataSource identityDataSource,
            PasswordEncoder passwordEncoder,
            @Value("${AUTH_DEMO_USER}") String username,
            @Value("${AUTH_DEMO_PASSWORD}") String password) {
        JdbcUserDetailsManager manager = new JdbcUserDetailsManager(identityDataSource);
        if (!manager.userExists(username)) {
            manager.createUser(User.withUsername(username)
                    .password(passwordEncoder.encode(password))
                    .roles("USER").build());
        } else {
            String encoded = manager.loadUserByUsername(username).getPassword();
            if (!passwordEncoder.matches(password, encoded))
                throw new IllegalStateException(
                        "Persisted demo user's password differs; use an approved migration");
        }
        return manager;
    }

    @Bean
    public RegisteredClientRepository jdbcRegisteredClients(DataSource identityDataSource,
            PasswordEncoder encoder,
            @Value("${AUTH_WRITER_CLIENT_SECRET}") String writerSecret,
            @Value("${AUTH_READER_CLIENT_SECRET}") String readerSecret,
            @Value("${AUTH_FULFILLMENT_CLIENT_SECRET}") String fulfillmentSecret,
            @Value("${AUTH_RETURNS_CLIENT_SECRET}") String returnsSecret,
            @Value("${AUTH_MODERATOR_CLIENT_SECRET}") String moderatorSecret,
            @Value("${AUTH_DEMO_USER}") String demoUser,
            @Value("${AUTH_DEMO_PASSWORD}") String demoPassword) {
        // JDBC mode always enforces the strong credential policy.
        AuthCredentialPolicy.validate(true, demoUser, demoPassword,
                writerSecret, readerSecret, fulfillmentSecret, returnsSecret, moderatorSecret);

        RegisteredClientRepository templates = AuthorizationServerConfig.buildDemoClientTemplates(
                writerSecret, readerSecret, fulfillmentSecret, returnsSecret,
                moderatorSecret, demoUser, demoPassword, true, encoder);
        JdbcRegisteredClientRepository stored = new JdbcRegisteredClientRepository(new JdbcTemplate(identityDataSource));
        String[] rawSecrets = {
                writerSecret, readerSecret, fulfillmentSecret, returnsSecret, moderatorSecret
        };
        for (int index = 0; index < CLIENT_IDS.size(); index++) {
            String clientId = CLIENT_IDS.get(index);
            RegisteredClient expected = templates.findByClientId(clientId);
            RegisteredClient existing = stored.findByClientId(clientId);
            if (existing == null) {
                stored.save(expected);
            } else if (!encoder.matches(rawSecrets[index], existing.getClientSecret())
                    || !expected.getScopes().equals(existing.getScopes())
                    || !expected.getAuthorizationGrantTypes().equals(existing.getAuthorizationGrantTypes())
                    || !expected.getClientAuthenticationMethods().equals(existing.getClientAuthenticationMethods())
                    || !expected.getRedirectUris().equals(existing.getRedirectUris())) {
                throw new IllegalStateException(
                        "Persisted OAuth2 client differs from configured policy; use an approved migration");
            }
        }
        return stored;
    }
}
