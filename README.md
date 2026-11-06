# Amazon Clone Backend

Spring Boot 3.1 / Java 17 microservices prototype: product, reviews,
recommendations, composite product API, Spring Cloud Config, Eureka,
Gateway and OAuth2 authorization.

> This is **not** a complete shopping platform. Cart, inventory, checkout,
> payments, orders and customer accounts have not yet been implemented.

## Required setup

1. Revoke previously committed credentials, including the exposed GitHub token.
   Removing them from the latest commit does not erase them from Git history.
2. Copy `.env.example` to `.env` and replace example passwords.
3. This repository loads runtime settings from the separately maintained
   [config-repo](https://github.com/kakarot987/config-repo). Review and
   rotate the secrets there as well. Config settings in that repository must
   be consistent with your Compose environment overrides.
4. Install Java 17, Docker with Compose v2, and access to dependencies.

```sh
cp .env.example .env
# Edit .env before proceeding.
./gradlew clean bootJar
docker compose build
docker compose up -d
docker compose ps
./scripts/smoke-test.sh
```

The gateway is currently HTTP on localhost port **8443**, not HTTPS.
The development Compose config binds gateway, Eureka, Config Server and
Zipkin host ports to localhost. The remaining services run on the backend
Docker network. Never expose management endpoints publicly.

## Testing

The script verifies that the gateway becomes healthy and denies unauthenticated
catalog access. It accepts an existing OAuth2 access token for an optional
authenticated GET:

```sh
ACCESS_TOKEN="<access-token>" PRODUCT_ID=1 ./scripts/smoke-test.sh
```

The old `test-em-all.bash` assumes the removed OAuth2 password grant and
HTTPS on the gateway; do not treat it as a current end-to-end test.

## Notes and known limitations

- Runtime and integration tests have **not** been executed for this PR.
- Bootstrap depends on configuration from the separate public `config-repo`;
  the config repo still needs credential, network and data-source auditing.
- The OAuth2 server uses in-memory demo users/clients and must be replaced
  with a persistent identity solution for production use.
- Outbox-based event publishing, consumer idempotency, proper error handling
  and real production monitoring are not implemented.
- Spring Boot / Spring Cloud dependencies should be upgraded together after
  the current build and startup path is verified.
