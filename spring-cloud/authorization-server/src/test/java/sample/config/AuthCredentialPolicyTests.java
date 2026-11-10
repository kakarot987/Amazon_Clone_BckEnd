package sample.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthCredentialPolicyTests {
    private static final String[] STRONG = {
        "dev-writer-client-a-unique-secret-01",
        "dev-reader-client-b-unique-secret-02",
        "dev-fulfillment-c-unique-secret-03",
        "dev-returns-client-d-unique-secret-04",
        "dev-moderator-client-e-unique-secret-05"
    };

    @Test
    void demoModeKeepsExistingLocalDevelopmentCompatibility() {
        assertDoesNotThrow(() -> AuthCredentialPolicy.validate(false, "demo", "demo", "weak"));
    }

    @Test
    void strictModeAcceptsDistinctNonPlaceholderSecrets() {
        assertDoesNotThrow(() -> AuthCredentialPolicy.validate(true,
                "operator-demo", "strong-unique-demo-password-for-only-local-test", STRONG));
    }

    @Test
    void strictModeRejectsPlaceholderOrWeakCredentials() {
        assertThrows(IllegalStateException.class, () -> AuthCredentialPolicy.validate(true,
                "demo", "short-password", STRONG));
        String[] placeholders = STRONG.clone();
        placeholders[2] = "replace-with-unique-fulfillment-secret";
        assertThrows(IllegalStateException.class, () -> AuthCredentialPolicy.validate(true,
                "demo", "strong-unique-demo-password-for-only-local-test", placeholders));
    }

    @Test
    void strictModeRejectsDuplicateClientCredentials() {
        String[] duplicates = STRONG.clone();
        duplicates[1] = duplicates[0];
        assertThrows(IllegalStateException.class, () -> AuthCredentialPolicy.validate(true,
                "demo", "strong-unique-demo-password-for-only-local-test", duplicates));
    }

    @Test
    void strictModeRejectsPasswordReusedAsClientSecret() {
        assertThrows(IllegalStateException.class, () -> AuthCredentialPolicy.validate(true,
                "demo", STRONG[0], STRONG));
    }
}
