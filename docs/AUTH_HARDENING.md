# Authentication signing key and credential hardening

The auth server still uses **in-memory demo users and OAuth2 clients**. This increment makes a persistent RSA signing key available for local demonstrations and adds optional startup credential checks. It is **not** a production identity provider.

## Default mode

Default Docker Compose retains current behavior: the auth server generates an ephemeral RSA signing key on startup. Previously issued JWTs may fail validation after restart. This is suitable only for disposable local development, not production.

## Opt-in persistent signing key

1. Replace **all** example passwords and client secrets in your local, uncommitted `.env`. Strict mode requires the demo password to be at least 20 characters and all five OAuth2 client secrets to be unique, non-placeholder strings of at least 24 characters.
2. Set `AUTH_JWT_KEYSTORE_PASSWORD` and `AUTH_JWT_KEY_PASSWORD` to the same fresh random value (24+ characters), and set `AUTH_JWT_KEY_ALIAS=jwt-signing`. The variables must be exported into the shell before generating the key; merely writing `.env` does not export it.
3. Generate the key with JDK 17 `keytool` (the script won't overwrite an existing key):

```bash
bash scripts/generate-auth-keystore.sh
docker compose -f docker-compose.yml -f docker-compose-auth-keys.yml config
docker compose -f docker-compose.yml -f docker-compose-auth-keys.yml up --build -d
```

The overlay mounts `./secrets/auth-signing.p12` read-only in the auth container and sets both `AUTH_SECURITY_STRICT=true` and `AUTH_REQUIRE_PERSISTENT_KEYS=true`. All passwords and keystore files must remain local and be protected with filesystem permissions. Do not display their contents or commit them.

4. Verify the **public** JWKS key ID (`kid`), restart `auth-server` and confirm the same key ID remains. Existing unexpired JWTs should remain verifiable as long as the same key is loaded and the issuer remains unchanged. The default Compose does not publish an auth-server host port; configure private access for a local JWKS check without exposing private key material.

Strict mode fails startup if a persistent key is missing or invalid; the PKCS12 alias must contain a currently valid RSA X.509 private key with at least 2048 bits and matching public key. The public key fingerprint provides a deterministic `kid`.

## Security boundaries

- `AUTH_SECURITY_STRICT=true` enforces persistent key configuration and strong, distinct local demo credentials. `AUTH_REQUIRE_PERSISTENT_KEYS=true` independently requires a persistent key.
- Credential length/placeholder checking is **not** entropy measurement or credential rotation.
- This implementation publishes **one** signing key. Before rotating keys in a real deployment, add JWKS overlap/key-ring support or old valid tokens will stop verifying.
- No persistent customer identities, MFA, credential revocation, durable client registration, rate limits, TLS termination or authorization-code storage is added.
- A PKCS12 key must be securely backed up and its passwords protected; losing it invalidates existing JWTs. Use managed secrets for any real deployment.
- The default gateway listens over **HTTP** on port 8443. The port number is not HTTPS.

## Tests

The new `SigningKeyLoaderTests` generate disposable test-only PKCS12 keys using the local JDK's `keytool`, assert stable `kid` across reloads and reject missing/incorrect credentials. `AuthCredentialPolicyTests` exercise strict and local-demo modes, duplicate secrets and placeholders. CI executes them alongside existing OAuth2 tests.

This improvement reduces restart-related JWT breakage and prevents a class of weak demo credentials in strict mode, but does **not** complete the P0 persistent identity roadmap.

## CI diagnostics

The Java CI workflow first checks that the Docker daemon is reachable, then runs Gradle and Testcontainers. A `DockerClientProviderStrategy`/`ExceptionInInitializerError` indicates Docker discovery or daemon access failed; it is **not** evidence that all Java assertions failed. If the Docker preflight passes but Testcontainers fails later, inspect the first Testcontainers exception and uploaded test reports. The Java 17 build must pass both compilation and the integration suite before a merge is considered verified.
