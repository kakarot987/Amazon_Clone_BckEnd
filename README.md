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

## Reservation expiration and recovery (Phase 2, draft)

Unpaid, single-item `RESERVED` orders expire after **15 minutes** by
default. Expiry starts when the order intent is created. Configurable
settings for the product service:

```yaml
app:
  orders:
    reservation-ttl: PT15M
    recovery-interval: PT30S
    recovery-batch-size: 100
```

The product service periodically retries orders in `PENDING`,
`CANCELLATION_PENDING` and `EXPIRATION_PENDING` and expires overdue
`RESERVED` orders. The release flow records `EXPIRATION_PENDING`
before returning stock, and then moves to `EXPIRED`. Order receipts
include `expiresAt`. When the process crashes between operations,
the next recovery pass retries unfinished work. Each product's
reservation-ID list prevents double stock deduction and double release.

A `RESERVED` order is **never paid** in this prototype. Expiry does
not perform a refund, capture, settlement or fulfillment cancellation.
A retry of the same checkout idempotency key returns the prior order,
even if it is expired; use a new key to create a new order intent.

**Operational limitations:** The recovery pass scans order documents
and processes up to 100 unfinished/expired items per interval by
default. Very large deployments need indexed status/expiry queries,
observability/alerts, retention and a separately audited reservation
ledger. Order and product documents are still **not in a MongoDB
transaction**. Crash recovery is best effort, and pending orders that
cannot reserve stock continue to require investigation. Before any
real payment integration, add distributed worker coordination and
stress-test conflicting orders, cancellations, inventory edits and
process crashes.

## Mock payments — full simulation phase (draft)

This phase adds an **end-to-end simulated payment workflow**, including
initiation, an HMAC-signed provider callback, durable order/payment state,
idempotent callback retries, failed-payment stock release, and recovery.
**No real payment credentials, cards, bank accounts, charges, refunds, or
payment provider integrations are involved.**

### Configure

Generate a unique development-only signing key:

```sh
python3 -c 'import secrets; print(secrets.token_urlsafe(48))'
```

Put it in your ignored `.env` as `MOCK_PAYMENT_WEBHOOK_SECRET`.
Do not commit it or reuse it as a live provider key. The product-service
container receives this secret; the gateway/composite do not. Webhook
verification fails closed if the key is absent or a placeholder.

### End-to-end local simulation

1. Create a product with nonzero available stock and a price/currency.
2. Add exactly one item to a signed-in customer's cart.
3. Create a reserved order:

```http
POST /product-composite/orders
Authorization: Bearer <signed-in-user-order-write-token>
Idempotency-Key: checkout-unique-request-1234
```

4. Use the resulting 64-character `orderId` to initialize the payment
   intent (same `order:write` scope):

```http
POST /product-composite/payments/orders/{orderId}/intent
Authorization: Bearer <signed-in-user-order-write-token>
```

   This returns a `PaymentIntent` with `paymentId` equal to
   `orderId`, the authoritative total/currency, state and expiry.
   Repeating initiation returns the same intent.
5. Export the **same** mock signing key used by product-service, then
   simulate success or decline:

```sh
export MOCK_PAYMENT_WEBHOOK_SECRET='<your-development-mock-key>'
python3 scripts/send-mock-payment.py --payment-id '<order-id>' --outcome SUCCEEDED
# Or create a fresh order and send --outcome DECLINED
```

6. Read order status with `GET /product-composite/orders/{orderId}`
   using an `order:read` user token.

The simulator calls `POST /product-composite/payments/mock-webhook`
with the JSON bytes, `X-Mock-Timestamp` (Unix epoch seconds), and
`X-Mock-Signature` (lowercase hex HMAC-SHA256 of
`<timestamp>.<exact raw JSON body>`). The webhook is publicly reachable
on the development gateway but **requires a valid HMAC**. Missing or
invalid signatures and timestamps outside a 5-minute skew window are
rejected. To test duplicate delivery, pass the same `--event-id`
and repeat the command.

### Payment states and reservation guarantees

- `PAYMENT_PENDING`: mock payment initiated; stock remains reserved.
- `PAYMENT_CONFIRMING`: signed simulated success accepted; finalization
  removes the reservation token *without* increasing available stock.
- `MOCK_PAID`: terminal simulated success (**not a real payment**).
- `PAYMENT_FAILED_PENDING`: signed mock decline accepted; release in progress.
- `PAYMENT_FAILED`: terminal decline; held stock restored once.
- `EXPIRED` / `CANCELLED`: previously reserved stock restored.
  Late mock-success callbacks are rejected, not converted to paid orders.

The recovery worker retries interrupted confirmation/release and expires
`PAYMENT_PENDING` intents if the underlying reservation TTL passes.
The first valid signed outcome wins; further conflicting events are
rejected. Replays of the same signed event are idempotent while within
the allowed timestamp window.

**Limitations:** The mock provider script intentionally controls the
outcome; it is **not** payment authorization. The payment state is
embedded in the order document; there is no external provider ledger,
immutable webhook event archive, reconciliation with a real processor,
refund/capture API, settlement, chargebacks, or multi-item checkout.
Order and stock records remain separate Mongo documents with best-effort
recovery. Real payment integration requires audited provider reconciliation,
an outbox, idempotency storage and stronger transactional guarantees.
