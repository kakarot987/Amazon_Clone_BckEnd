package sample.jose;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SigningKeyLoaderTests {
    @TempDir Path temp;

    private static final String PASSWORD = "test-only-password-not-for-deployment";
    private static final String ALIAS = "jwt-signing";

    @Test
    void strictModeRequiresPersistentKey() {
        assertThrows(IllegalStateException.class,
                () -> SigningKeyLoader.loadOrGenerate("", "", ALIAS, "", true));
    }

    @Test
    void demoModeCanGenerateEphemeralKeys() {
        RSAKey first = SigningKeyLoader.loadOrGenerate("", "", "", "", false);
        RSAKey second = SigningKeyLoader.loadOrGenerate("", "", "", "", false);
        assertNotEquals(first.getKeyID(), second.getKeyID(),
                "Ephemeral development signing keys must not be mistaken for persistent keys");
    }

    @Test
    void rejectsMissingOrInvalidKeystoreConfiguration() {
        Path missing = temp.resolve("missing.p12");
        assertThrows(IllegalStateException.class,
                () -> SigningKeyLoader.loadOrGenerate(
                        missing.toString(), PASSWORD, ALIAS, PASSWORD, true));
        assertThrows(IllegalStateException.class,
                () -> SigningKeyLoader.loadOrGenerate(
                        missing.toString(), "", ALIAS, PASSWORD, true));
        assertThrows(IllegalStateException.class,
                () -> SigningKeyLoader.loadOrGenerate(
                        "", PASSWORD, ALIAS, PASSWORD, false));
    }

    @Test
    void persistentKeystoreKeepsSigningKeyAndKidAcrossLoads() throws Exception {
        Path file = temp.resolve("temporary-auth-test.p12");
        generateKeyStore(file, 2048);
        RSAKey first = SigningKeyLoader.loadOrGenerate(
                file.toString(), PASSWORD, ALIAS, PASSWORD, true);
        RSAKey second = SigningKeyLoader.loadOrGenerate(
                file.toString(), PASSWORD, ALIAS, PASSWORD, true);
        assertEquals(first.getKeyID(), second.getKeyID());
        assertEquals(first.toPublicJWK(), second.toPublicJWK());
        assertEquals(first.toRSAPrivateKey(), second.toRSAPrivateKey());
        assertTrue(first.getKeyID().startsWith("rsa-"));
        assertNotNull(first.toPublicJWK());
    }

    @Test
    void rejectsIncorrectStorePassword() throws Exception {
        Path file = temp.resolve("bad-password-test.p12");
        generateKeyStore(file, 2048);
        assertThrows(IllegalStateException.class,
                () -> SigningKeyLoader.loadOrGenerate(
                        file.toString(), "incorrect-password", ALIAS, PASSWORD, true));
    }

    private void generateKeyStore(Path file, int bits) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", "keytool")
                .toAbsolutePath().toString();
        Process command = new ProcessBuilder(
                executable, "-genkeypair", "-alias", ALIAS,
                "-keyalg", "RSA", "-keysize", Integer.toString(bits),
                "-storetype", "PKCS12", "-keystore", file.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD,
                "-dname", "CN=Temporary Auth Unit Test", "-validity", "3",
                "-noprompt").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!command.waitFor(30, TimeUnit.SECONDS)) {
            command.destroyForcibly();
            fail("Timed out generating the temporary test PKCS12 key");
        }
        assertEquals(0, command.exitValue(), "JDK keytool should generate test-only signing key");
    }
}
