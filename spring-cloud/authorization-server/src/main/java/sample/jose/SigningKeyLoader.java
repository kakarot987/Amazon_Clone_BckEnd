package sample.jose;

import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.HexFormat;

/**
 * Loads an operator-managed RSA signing key from a mounted PKCS#12 keystore.
 * With no configured path, ephemeral keys are permitted ONLY in local/demo
 * mode. No silent key generation is allowed in production or strict mode.
 */
public final class SigningKeyLoader {
    private static final Logger LOG = LoggerFactory.getLogger(SigningKeyLoader.class);
    private SigningKeyLoader() {}

    public static RSAKey loadOrGenerate(String path, String password, String alias,
                                        String keyPassword, boolean requirePersistent) {
        if (path == null || path.isBlank()) {
            if (requirePersistent)
                throw new IllegalStateException("Persistent JWT signing key required; configure AUTH_JWT_KEYSTORE_PATH");
            if (hasValue(password) || hasValue(keyPassword))
                throw new IllegalStateException("JWT keystore password configured without a keystore path");
            LOG.warn("DEMO ONLY: issuing tokens with an ephemeral RSA key. Existing JWTs will fail after restart.");
            return Jwks.generateRsa();
        }
        if (!hasValue(password) || !hasValue(alias) || !hasValue(keyPassword))
            throw new IllegalStateException("JWT keystore path, password, alias and key password are all required");

        try {
            Path file = Path.of(path);
            if (!Files.isRegularFile(file))
                throw new IllegalStateException("Configured JWT keystore is not a regular readable file");
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(file)) {
                store.load(input, password.toCharArray());
            }
            Key key = store.getKey(alias, keyPassword.toCharArray());
            Certificate cert = store.getCertificate(alias);
            if (!(key instanceof RSAPrivateKey privateKey)
                    || !(cert instanceof X509Certificate x509)
                    || !(cert.getPublicKey() instanceof RSAPublicKey publicKey))
                throw new IllegalStateException("JWT signing alias must reference an X.509 RSA private-key entry");
            x509.checkValidity();
            if (publicKey.getModulus().bitLength() < 2048)
                throw new IllegalStateException("JWT signing RSA key must be at least 2048 bits");
            if (!publicKey.getModulus().equals(privateKey.getModulus()))
                throw new IllegalStateException("JWT keystore public/private RSA keys do not match");

            // A stable, non-secret kid lets consumers cache JWKS across restarts.
            // Explicit key rotation is required; old tokens need the old public
            // key retained during overlap, which is not implemented here.
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
            String kid = "rsa-" + HexFormat.of().formatHex(digest, 0, 16);
            LOG.info("Loaded persistent JWT signing key (kid={})", kid);
            return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(kid).build();
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            // Never include passwords, private key bytes or original exception
            // messages (which could contain sensitive file-system details).
            throw new IllegalStateException(
                    "Unable to load persistent JWT signing key: check file, alias, certificate and passwords",
                    failure);
        }
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }
}
