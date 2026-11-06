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

## Shopping cart and inventory (Phase 1, draft)

These endpoints use the existing `/product-composite/**` Gateway route.
JWT scopes for the demo authorization server:

- `cart:read` — view the signed-in user's cart
- `cart:write` — set or remove the signed-in user's cart items
- `inventory:read` — inspect the available stock of a product
- `inventory:write` — privileged, atomic stock adjustments

Example routes (send `Authorization: Bearer <signed-in-user-access-token>`):

```http
GET /product-composite/cart
PUT /product-composite/cart/items/77
Content-Type: application/json

{"quantity":3}
```

```http
DELETE /product-composite/cart/items/77
GET /product-composite/inventory/77
POST /product-composite/inventory/77/adjustments
Content-Type: application/json

{"delta":-2}
```

Cart persistence uses MongoDB `shopping_carts` documents with optimistic
concurrency retries and at most 50 distinct products, each with quantity
1–99. The server hashes the verified token issuer and user subject to derive
cart identity; clients cannot supply an owner ID. App-only (client-credentials)
tokens cannot access personal carts. The internal `/internal/**` endpoints
must never be exposed to public clients or trusted proxies.

**Important limitations:** A cart does not reserve stock. Price and product
name in the cart are *display-only snapshots* and must be revalidated at
checkout. Inventory adjustments are single-document atomic increments with
nonnegative-stock checks, not transactionally durable reservations or
idempotent order deductions. Do not use stock adjustment as a payment or
checkout API. Old products with null stock must be initialized before
they can enter a cart.

This phase does not implement user registration, order placement, checkout,
payments, reservation expiration or stock ledger/idempotency. Before
production, create a dedicated inventory/order flow with reservation IDs,
expiry, and audit records. Full Gradle/Docker tests are still required.

## Orders and checkout foundations (Phase 1, draft)

This milestone provides **single-product** order intents with atomic stock
reservation, idempotency and cancellation. **No payments are charged** and
there is no fulfillment/shipping process.

A signed-in user token is required: `order:read` for reads and
`order:write` for creating or cancelling a reservation. App-only
client-credentials tokens cannot operate on customer orders.

```http
POST /product-composite/orders
Idempotency-Key: checkout-attempt-12345

GET /product-composite/orders/{orderId}
POST /product-composite/orders/{orderId}/cancel
```

Checkout uses the **current saved cart**, and currently requires exactly
one item. Price/currency are always re-read from product data rather than
trusted from the cart display snapshot. Each new checkout returns an
order receipt with the total, currency, reserved product/quantity, and
status. Use a new idempotency key for each distinct checkout attempt;
reusing a key returns the original order, even if the cart has changed.

Statuses: `PENDING` (order intent saved; stock reservation not finalized),
`RESERVED` (stock reserved, **not paid**),
`CANCELLATION_PENDING` (release in progress), `CANCELLED` (released).
Repeat the original checkout request with the same key if it stalls in
`PENDING`; repeat cancellation if it stalls in
`CANCELLATION_PENDING`. Recovery automation is not implemented.

The MongoDB reservation uses one atomic document update that decrements
available stock **and** records the order ID in `reservedOrderIds`.
Repeated operations cannot decrement/release the same reservation twice.
The order record and product stock are in separate documents, so a crash
between them can leave a pending operation that must be retried/reconciled.
This is intentionally not a complete transactional checkout implementation.

Current restrictions: No multi-product checkout, expiry, automatic
recovery worker, delivery, tax/shipping calculation, payment integration,
refunds, completed orders, or reservation audit ledger. Products with
active reservations cannot be edited or deleted using the existing
catalog API. Existing products must have stock and price configured
to be eligible. Do not treat this as production checkout without
end-to-end tests, operation monitoring and a reconciliation process.
