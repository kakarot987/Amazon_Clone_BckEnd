package sample.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JdbcIdentityConfigurationTests {
    private static final String DEMO_USER = "persistent-demo-user";
    private static final String DEMO_PASSWORD = "local-only-long-unique-demo-password-1234";
    private static final String[] SECRETS = {
            "writer-long-unique-secret-00001",
            "reader-long-unique-secret-00002",
            "fulfillment-long-unique-secret-03",
            "returns-long-unique-secret-00004",
            "moderator-long-unique-secret-05"
    };

    private HikariDataSource create(String databaseName) {
        return new JdbcIdentityConfiguration().identityDataSource(
                "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "test-only-local-password");
    }

    private RegisteredClientRepository clients(JdbcIdentityConfiguration configuration,
                                               HikariDataSource dataSource,
                                               PasswordEncoder passwordEncoder,
                                               String[] credentials) {
        return configuration.jdbcRegisteredClients(dataSource, passwordEncoder,
                credentials[0], credentials[1], credentials[2], credentials[3],
                credentials[4], DEMO_USER, DEMO_PASSWORD);
    }

    @Test
    void flywayCreatesSchemasAndStoredCredentialsSurviveReload() {
        JdbcIdentityConfiguration config = new JdbcIdentityConfiguration();
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        try (HikariDataSource database = create("identity_" + UUID.randomUUID().toString().replace("-", ""))) {
            var users = config.jdbcUsers(database, encoder, DEMO_USER, DEMO_PASSWORD);
            RegisteredClientRepository first = clients(config, database, encoder, SECRETS);
            String originalId = first.findByClientId("writer").getId();
            String originalHash = first.findByClientId("writer").getClientSecret();
            String userHash = users.loadUserByUsername(DEMO_USER).getPassword();

            // Simulate a new app instance using the same database.
            var usersAfterRestart = config.jdbcUsers(database, encoder, DEMO_USER, DEMO_PASSWORD);
            RegisteredClientRepository reloaded = clients(config, database, encoder, SECRETS);
            UserDetails identity = usersAfterRestart.loadUserByUsername(DEMO_USER);

            assertEquals(originalId, reloaded.findByClientId("writer").getId());
            assertEquals(originalHash, reloaded.findByClientId("writer").getClientSecret());
            assertEquals(userHash, identity.getPassword());
            assertTrue(encoder.matches(DEMO_PASSWORD, identity.getPassword()));
            assertEquals(5, new JdbcTemplate(database)
                    .queryForObject("SELECT COUNT(*) FROM oauth2_registered_client", Integer.class));
            assertEquals(1, new JdbcTemplate(database)
                    .queryForObject("SELECT COUNT(*) FROM users", Integer.class));
        }
    }

    @Test
    void refusesSilentClientCredentialRotation() {
        JdbcIdentityConfiguration config = new JdbcIdentityConfiguration();
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        try (HikariDataSource database = create("identity_" + UUID.randomUUID().toString().replace("-", ""))) {
            clients(config, database, encoder, SECRETS);
            String[] changed = SECRETS.clone();
            changed[0] = "writer-long-changed-secret-00001";
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> clients(config, database, encoder, changed));
            assertTrue(error.getMessage().contains("Persisted OAuth2 client"));
        }
    }

    @Test
    void refusesSilentUserPasswordChanges() {
        JdbcIdentityConfiguration config = new JdbcIdentityConfiguration();
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        try (HikariDataSource database = create("identity_" + UUID.randomUUID().toString().replace("-", ""))) {
            config.jdbcUsers(database, encoder, DEMO_USER, DEMO_PASSWORD);
            assertThrows(IllegalStateException.class, () ->
                    config.jdbcUsers(database, encoder, DEMO_USER,
                            "changed-and-long-unique-demo-password-9999"));
        }
    }

    @Test
    void missingJdbcCredentialsFailClosed() {
        JdbcIdentityConfiguration config = new JdbcIdentityConfiguration();
        assertThrows(IllegalStateException.class, () ->
                config.identityDataSource("jdbc:h2:mem:unsafe", "sa", ""));
        assertThrows(IllegalStateException.class, () ->
                config.identityDataSource("jdbc:unsupported:unsafe", "sa", "password"));
    }
}
