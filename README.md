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

## Product Catalog V2 (draft)

Legacy product creation and `GET /product-composite/{id}` still work.

Optional metadata fields: `sku`, `description`, `category`, `price`
(decimal), `currency` (ISO 4217), `stockQuantity` (nonnegative),
`imageUrls` (HTTPS URLs). Existing Mongo documents continue to deserialize
with null values for the new fields; no destructive migration is required.

Via Gateway (JWT `product:read` / `product:write` scopes):

- `GET /product-composite?query=shoes&category=footwear&page=0&size=20`
  returns `{products, page, size, totalElements, totalPages}`.
- `PUT /product-composite/{id}` accepts a full product document and returns
  `202 Accepted` when an UPDATE event is published. Read-after-write is
  eventually consistent; the consumer rejects updates to missing products.
- `POST /product-composite` supports the new optional fields and retains
  existing review/recommendation creation.

The internal `product` service provides `GET /products` and
`PUT /product/{id}` for internal integration; those ports should remain
private. The listing is sorted by `productId`, filters category exactly and
uses case-insensitive substring matching across name, description and SKU.
Page sizes are capped at 100. For large catalogs, adopt proper full-text
indexes/search infrastructure and cursor pagination.

This is catalog metadata only: stockQuantity is **not** reserved inventory,
SKU is not yet enforced unique, and price changes do not implement checkout,
tax, or currency conversion. Validation and event-delivery guarantees must be
verified before deploying.
