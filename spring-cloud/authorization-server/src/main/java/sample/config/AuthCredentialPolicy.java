package sample.config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Fail-closed credential checks when strict auth mode is enabled.
 * Development demo credentials are still allowed when strict mode is off.
 */
public final class AuthCredentialPolicy {
    private AuthCredentialPolicy() {}

    public static void validate(boolean strict, String demoUser, String demoPassword,
                                String... clientSecrets) {
        if (!strict) return;
        if (demoUser == null || demoUser.isBlank()
                || demoPassword == null || demoPassword.length() < 20
                || isPlaceholder(demoPassword))
            throw new IllegalStateException("Strict auth mode requires a non-demo username and strong password");

        if (clientSecrets == null || clientSecrets.length != 5)
            throw new IllegalStateException("Strict auth mode requires all five OAuth2 client secrets");

        Set<String> unique = new HashSet<>();
        for (String secret : clientSecrets) {
            if (secret == null || secret.length() < 24 || isPlaceholder(secret)
                    || !unique.add(secret))
                throw new IllegalStateException(
                        "Strict auth mode requires unique, non-placeholder OAuth2 client secrets of at least 24 characters");
        }
        if (unique.contains(demoPassword))
            throw new IllegalStateException("Demo password must not reuse an OAuth2 client secret");
    }

    private static boolean isPlaceholder(String secret) {
        String value = secret.toLowerCase(java.util.Locale.ROOT);
        return value.contains("replace-") || value.contains("changeme")
                || value.equals("password") || value.startsWith("test-only-");
    }
}
