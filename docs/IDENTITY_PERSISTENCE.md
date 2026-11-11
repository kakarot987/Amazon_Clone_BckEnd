# Optional JDBC identity persistence

The authorization server's default **demo mode remains unchanged**: a single in-memory demo user, five in-memory OAuth2 clients and an ephemeral RSA signing key unless the persistent-key override is used.

This increment introduces an explicit `AUTH_IDENTITY_STORE=jdbc` mode for **durable demo-user credentials and registered OAuth2 clients**. The schema uses versioned Flyway migrations. The database stores password hashes and OAuth2 client-secret hashes (never plaintext secrets), and startup refuses changed credentials or client authorization policy rather than silently overwriting them.

## Local opt-in

For disposable local Docker Compose, configure strong unique `AUTH_*_CLIENT_SECRET` values and `AUTH_DEMO_PASSWORD` (strict mode checks these), plus a local JWT key as described in [auth hardening](AUTH_HARDENING.md). Add new values to the **uncommitted** `.env`:

```dotenv
AUTH_DB_PASSWORD=replace-with-a-unique-long-local-auth-db-password
AUTH_DB_ROOT_PASSWORD=replace-with-a-different-long-local-auth-db-root-password
```

Use three Compose layers in order:

```bash
docker compose -f docker-compose.yml \
  -f docker-compose-auth-keys.yml \
  -f docker-compose-auth-jdbc.yml config

docker compose -f docker-compose.yml \
  -f docker-compose-auth-keys.yml \
  -f docker-compose-auth-jdbc.yml up --build -d
```

Generate the private signing key **before** startup using `scripts/generate-auth-keystore.sh`. The auth database is a separate MySQL service and volume; no auth database port is published publicly. Database password changes require an approved MySQL credential rotation, not simply changing environment variables on an existing volume.

The auth server automatically applies `V1__identity_schema.sql` on its **own identity database**. Existing `review-db` tables and the review-service's MySQL schema are untouched.

## Restart behavior

- Persisted demo users and OAuth2 registered clients survive restarting/recreating the auth-server container as long as the `auth-mysql-data` volume is retained.
- The code creates initial records once. On subsequent starts it validates the configured user password and registered-client credentials, grants, scopes and redirect URIs. It will **fail startup** on drift; modify these only through reviewed migrations and rotation procedures.
- The persisted client ID/secret hash does not change when the auth container restarts. Key stability requires the separate mounted PKCS12 signing key.
- A live auth server needs **persistent authorization/consent records** for full session and refresh-token continuity. This milestone deliberately does **not** persist the OAuth2 authorization service or consent store. It must not be described as supporting durable auth sessions or production customer registration.
- This mode seeds only the known local demo user. Account signup, password reset, MFA, identity-proofing, account locking, token revocation, and customer administration are **not implemented**.

## Security/operations limitations

Use managed secrets, TLS, explicit privileges, backups, staged schema migrations, and credential-rotation procedures in any real deployment. The local default gateway still exposes HTTP, even though it uses port 8443. Flyway database migrations happen on startup; verify permissions and backups before upgrading an existing auth database. Do not copy raw JWTs, private keys, hashed passwords, or database credentials into screenshots/logs.

The authentication database must have reliable backups and access controls; a separate Docker volume is not a backup. OAuth2 grants stored in memory do not survive service restart.

## CI coverage

`JdbcIdentityConfigurationTests` use H2/MySQL-compatible schema syntax to exercise Flyway schema creation, stable user/client identities and hashes across object reloads, rejection of changed stored credentials, and missing database secrets. They do not replace a real Docker Compose MySQL integration test, which should be run locally before enabling the overlay.

This phase makes identities **persistent in an explicit opt-in mode**, not a fully production-hardened identity platform.
