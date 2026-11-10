# Amazon Clone Backend

Spring Boot 3.1 / Java 17 microservices prototype: product, reviews,
recommendations, composite product API, Spring Cloud Config, Eureka,
Gateway and OAuth2 authorization.

> This is a **microservices prototype**, not a production commerce platform.
> All payments, carrier tracking and fulfillment operations are simulated.

## Verified build tooling

The repository previously included an unrecognized Gradle Wrapper JAR that
GitHub Actions correctly rejected before running tests. The legacy wrapper
binary and launcher scripts have been removed **rather than bypassing
wrapper-integrity validation**. Install **Gradle 8.6** from the official Gradle
distribution or use a trusted package manager. On CI, the official
`gradle/actions/setup-gradle@v4` action installs Gradle 8.6. Build with:

```sh
gradle --version
gradle clean build
```

Do not restore the old wrapper JAR. To reintroduce a wrapper later, regenerate
it from an independently verified Gradle installation and require GitHub
Actions wrapper validation to pass.

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
gradle clean bootJar
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

## Complete simulated fulfillment & shipment tracking phase (draft)

This phase adds **mock** shipping and tracking for the single-product,
`MOCK_PAID` orders created in the earlier payment phase. It does **not**
contact real shipping carriers, print shipping labels, allocate a warehouse,
collect a physical address, charge a credit card or fulfill a real order.
The destination is a simple 2–32-character *zone code*, not an address.

### Separation of roles

Customers use their normal signed-in user token with `order:read` scope
to retrieve tracking. Operators use a **separate OAuth client** called
`mock-fulfillment`, configured with `AUTH_FULFILLMENT_CLIENT_SECRET` in
the ignored `.env`. Only this service client has `fulfillment:read`
and `fulfillment:write` scopes; the existing `writer` and `reader`
clients do not receive these permissions.

Set a fresh, non-default `AUTH_FULFILLMENT_CLIENT_SECRET` in `.env`
and recreate the `auth-server` container. For a development-only service
token, from the host with auth-server reachable:

```sh
# Auth server may not be host-exposed; use a trusted internal client instead
# or a port forward limited to localhost, never public port exposure.
curl -u "mock-fulfillment:$AUTH_FULFILLMENT_CLIENT_SECRET" \
  -d grant_type=client_credentials \
  -d 'scope=fulfillment:read fulfillment:write' \
  http://localhost:9999/oauth2/token
```

Do not put tokens in source control, shell history or CI logs. The example
requires a localhost-accessible development auth-server; it is **not**
configured for public access by default.

### Routes (through authenticated API Gateway)

| Role | Method | Path | Scope |
|---|---|---|---|
| Customer | GET | `/product-composite/orders/{orderId}/shipment` | `order:read` |
| Operator | POST | `/product-composite/fulfillment/shipments` | `fulfillment:write` |
| Operator | GET | `/product-composite/fulfillment/shipments/{orderId}` | `fulfillment:read` |
| Operator | POST | `/product-composite/fulfillment/shipments/{orderId}/events` | `fulfillment:write` |

Create a shipment for a **mock-paid** order only:

```json
{"orderId":"<64-character-order-id>","destinationZone":"NORTH_01"}
```

Repeat the same creation request safely; one shipment document is stored per
order. A different zone on a replay is rejected.

Advance a shipment sequentially with a unique event ID:

```json
{"eventId":"mock-event-0001","status":"PACKED"}
```

State machine:

```text
CREATED -> PACKED -> SHIPPED -> IN_TRANSIT -> DELIVERED
```

Skipping milestones, moving backward or changing a `DELIVERED` shipment is
rejected. Repeating an event with the same ID and outcome is idempotent,
including after later milestones; the history is persisted with timestamps
and Mongo optimistic concurrency checks. A reused event ID with a different
outcome is rejected. Tracking numbers are `MOCK-` prefixed placeholders,
not carrier tracking numbers.

An included standard-library Python simulator can create or advance events
with an operator token:

```sh
export FULFILLMENT_ACCESS_TOKEN='<scoped-operator-access-token>'
python3 scripts/simulate-shipment.py create --order-id '<order-id>' --zone NORTH_01
python3 scripts/simulate-shipment.py advance --order-id '<order-id>' --status PACKED
python3 scripts/simulate-shipment.py advance --order-id '<order-id>' --status SHIPPED
python3 scripts/simulate-shipment.py advance --order-id '<order-id>' --status IN_TRANSIT
python3 scripts/simulate-shipment.py advance --order-id '<order-id>' --status DELIVERED
```

A `MOCK_PAID` order cannot be cancelled using the existing unpaid-order
cancellation endpoint. Tracking reads require verified JWT identity;
a different customer cannot read another customer's shipment. Internal
`/internal/shipments/**` endpoints must remain private to Docker networks.

**Limitations:** No carrier API, physical pickup/delivery, refund, shipment
failure/return process, address encryption, webhooks, inventory pick ledger,
multi-item shipments, or payment fulfillment approval. The operator API is
secured by the mock OAuth client and gateway scopes, not by an enterprise
staff/warehouse identity service. Real commerce must use a dedicated
fulfillment system, audited access controls, returns/refunds and tested
delivery reconciliation. Run the Java 17 CI suite before merging.

## Returns, simulated refunds and post-delivery management (draft)

This phase builds on the simulated shipping workflow from PR #9. It supports
**one return per delivered single-product order**, with a separate operator
identity and full event history. **No real funds move**, no product is
automatically restocked, and `MOCK_REFUNDED` has no financial meaning.

### Eligibility and customer requests

Only the signed-in order owner can request a return. The order must be
`MOCK_PAID` and its mock shipment must have reached `DELIVERED`.
Requests are accepted for 30 days after the recorded delivery by default,
configured by `app.returns.window: P30D` in the product service. The
reason must be one of `DAMAGED`, `WRONG_ITEM`, `NOT_AS_DESCRIBED`,
or `NO_LONGER_NEEDED`. Free-text addresses and personal details are
intentionally not accepted.

```http
POST /product-composite/orders/{orderId}/return
Authorization: Bearer <signed-in-user-return-write-token>
Content-Type: application/json

{"reasonCode":"DAMAGED"}

GET /product-composite/orders/{orderId}/return
Authorization: Bearer <signed-in-user-return-read-token>
```

Repeated requests for the same order/reason return the existing record.
Changing the reason after creation is rejected. A different customer cannot
read or create a return on that order.

### Operator decisions and mock refund

A separate development OAuth2 client `mock-returns` holds
`returns:read` / `returns:manage`. Set a unique
`AUTH_RETURNS_CLIENT_SECRET` in your ignored `.env` file. Ordinary
customer and catalog writer credentials cannot approve or refund returns.

```http
GET  /product-composite/returns/{orderId}
POST /product-composite/returns/{orderId}/approve
POST /product-composite/returns/{orderId}/reject
POST /product-composite/returns/{orderId}/receive
POST /product-composite/returns/{orderId}/mock-refund
```

Each operator action requires `Authorization: Bearer <returns-operator-token>`
and a JSON body such as `{"eventId":"return-review-0001"}`. State
transitions are:

```text
REQUESTED -> APPROVED -> RECEIVED -> MOCK_REFUNDED
          -> REJECTED
```

Refund amount is taken from the original paid order's stored unit price
and quantity (not cart display values). Repeating the same event ID and
action does not duplicate a ledger entry; using the same ID for another
transition is rejected. The complete timestamped history is persisted with
the return record using optimistic locking and retries on conflicts.

A standard-library simulator is provided:

```sh
export RETURNS_ACCESS_TOKEN='<scoped-mock-returns-operator-token>'
python3 scripts/simulate-return.py --order-id '<order-id>' --action approve
python3 scripts/simulate-return.py --order-id '<order-id>' --action receive
python3 scripts/simulate-return.py --order-id '<order-id>' --action mock-refund
```

**Limitations:** The refund is a simulated state transition, not a payment
processor refund. A real integration would need provider refund IDs,
reconciliation, immutable audit records, monetary rounding/tax policy,
returns shipping labels, warehouse inspection, inventory restocking policy,
fraud checks, privacy protections and staff-level authorization. Returned
items are **not** automatically added to available stock.

### Merge verification

This PR also brings the shipment files from merged PR #9 onto `main`:
PR #9 had been merged into PR #8's branch *after* PR #8 was merged into
`main`, so its shipping changes did not appear on `main`. The new
branch is based directly on `main` and includes both the shipping code
and this complete mock returns phase. Run `gradle clean build` and
Docker end-to-end checks before merging.

## Customer wishlist & saved items (draft)

Wishlist items are saved **per signed-in user**, using the same opaque
JWT issuer+subject identity scheme as carts. Application-only client
credentials cannot access a personal wishlist. Customer-scoped
permissions are `wishlist:read` and `wishlist:write`.

```http
GET    /product-composite/wishlist?page=0&size=20
PUT    /product-composite/wishlist/items/{productId}
DELETE /product-composite/wishlist/items/{productId}
DELETE /product-composite/wishlist
```

**Behavior:** Saving is idempotent and supports out-of-stock items.
An item must exist when first saved. Each wishlist stores up to 100
distinct products in MongoDB with optimistic-concurrency retries;
pages are capped at 100 entries. Entries are sorted by save time
(newest first). Deleting a missing entry or clearing an empty wishlist
is harmless. Wishlist reads show **current** catalog name, category,
price, currency, stock availability and first image URL, not a stale
snapshot. If a previously saved product is deleted, its entry stays
with `productExists: false` and can still be removed.

The saved product ID and timestamp are the durable data; the current
catalog metadata is calculated on each read. No inventory is reserved,
prices are not guaranteed, and saving or removing items does not change
the cart or any order. Checkout must always revalidate stock and price.

**Limits:** A wishlist is not a stock-alert subscription, price-watch
system, recommendation algorithm or inventory hold. Public access
must pass through the authenticated Gateway; internal wishlist routes
must remain private. Run the Java 17 CI tests and end-to-end checks
before merging.

## Product discovery, customer reviews and rating summaries (draft)

This phase builds on the existing catalog and legacy review/recommendation
microservices. It adds **authenticated customer reviews**, a MySQL-based
ratings summary, and deterministic category-based discovery. It is not a
personalized recommender or a verified-purchase review system.

### Product discovery

All requests go through the authenticated Gateway with `product:read`:

```http
GET /product-composite?query=laptop&category=computers&inStock=true&sort=stockDesc&page=0&size=20
GET /product-composite/products/301/related?limit=6
```

`sort` supports `productId` (default), `productIdDesc`, `name` and
`stockDesc`. Other sort names are rejected. `inStock=true` matches
positive available stock, and `inStock=false` selects zero or untracked
stock. Search matches literal substrings of name/description/SKU using
case-insensitive regex with escaped user input; pagination is limited
to 100 records per page. This is not a full-text index or fuzzy search.
Related products have the same category, exclude the requested product,
sort deterministically by product ID and return at most 20 suggestions.
No similarity score, ranking personalization, advertisement, or user
behavior tracking is claimed.

### Verified-user identity and reviews

A new MySQL `customer_reviews` table with a unique
`(product_id, owner_key)` key stores **one customer review per product**.
The owner key is derived from the validated JWT issuer and subject in
the composite service; clients cannot supply a reviewer identity. App-only
client-credentials JWTs cannot submit or remove personal reviews.

```http
PUT /product-composite/products/301/my-review
Authorization: Bearer <signed-in-user-review-write-token>
Content-Type: application/json

{"rating":5,"title":"Excellent","content":"Useful for my work."}

DELETE /product-composite/products/301/my-review
Authorization: Bearer <signed-in-user-review-write-token>

GET /product-composite/products/301/reviews?page=0&size=20&sort=newest
Authorization: Bearer <review-read-token>

GET /product-composite/products/301/ratings
Authorization: Bearer <review-read-token>
```

Rating must be 1–5; title and content have length limits. Only the
signed-in customer can update or remove their review. Repeated identical
PUTs are idempotent. Public review responses omit private owner IDs.
Review listing supports `newest`, `oldest`, `highest` and `lowest`;
page sizes are capped at 100. The rating summary includes counts for
1–5 stars, review count and average rounded to two decimals.
The zero-review average is 0.

**Important:** Customer reviews in this table are separate from the
earlier demo/event-created `reviews` and `recommendations` endpoints.
Only customer reviews are counted in the new rating summary. A customer
is *not* required to have purchased the item to leave a review; do not
label these as verified purchases. No content moderation, spam detection,
abuse reports, administrative takedowns, or user privacy deletion jobs
are implemented yet.

### Deployment and verification

Make sure the review service can run MySQL schema migrations. This
prototype currently uses Spring Data JPA entities and the JPA auto-DDL
setting from the runtime configuration repository. Before production,
manage the new `customer_reviews` table, indexes and schema upgrades
with Flyway or Liquibase instead of relying on auto-DDL.

```sh
gradle clean build
docker compose build && docker compose up -d
./scripts/smoke-test.sh
```

The new customer review endpoints run internally on the review service;
do not publicly expose its port or allow clients to choose arbitrary
owner keys. Validate JWT scopes at both the composite and Gateway.

## Mock-purchase review provenance and abuse moderation (draft)

**The purchase indicator is simulated, not a verified real-world purchase.**
A review may display `mockPurchaseConfirmed: true` when the product service
finds a `MOCK_PAID` order for the authenticated reviewer and the exact
product ID. The composite service derives the owner from the verified JWT,
checks the product, then queries the private
`/internal/review-eligibility/{ownerKey}/product/{productId}` API. A
user cannot submit or overwrite the internal confirmation flag through
the public review API. Repeated edits preserve a previously confirmed
mock-purchase indicator. This is not evidence of actual payment, shipment,
or ownership of goods.

### Customer review reports

Review listing now exposes an opaque numeric `reviewId` used to flag
content. A signed-in user with `review:report` can call:

```http
POST /product-composite/products/{productId}/reviews/{reviewId}/report
Content-Type: application/json

{"reasonCode":"SPAM"}
```

Other reason codes are `ABUSE`, `OFF_TOPIC`, and `MISLEADING`.
Only one report per customer and review is persisted. Self-reporting,
reason changes, and arbitrary free-text PII are rejected. Reports do
not immediately hide a review; a moderator must make that decision.

### Independent mock moderator

Use a separate development-only OAuth client `mock-review-moderator`
with the `review:moderate` scope. Set a unique
`AUTH_MODERATOR_CLIENT_SECRET` in the ignored `.env`, then recreate
the auth-server container. No customer or ordinary writer client has
the moderation scope.

```http
GET /product-composite/moderation/reports?page=0&size=20
GET /product-composite/moderation/products/{productId}/reviews/{reviewId}

POST /product-composite/moderation/products/{productId}/reviews/{reviewId}/decision
Content-Type: application/json

{"eventId":"moderation-action-1234","action":"HIDE","reasonCode":"SPAM"}
```

Use `RESTORE` to make a hidden review public again. Audit event IDs
are unique and idempotent; conflicting replays are rejected. A hidden
review is excluded from public review pages **and the star-rating
average and distribution**, but remains available to authorized
moderation operators for investigation. Editing a hidden review does
not automatically make it visible.

The internal review service must remain private: its `/internal/**`
routes trust the composite gateway to enforce JWT scopes. The
moderation database uses transactions for visibility changes and audit
events. There is no automated moderation classifier, suspension,
appeal mechanism, reporter abuse scoring, impersonation-resistant real
purchase attestation, or immutable external audit sink.

**Schema warning:** The review service adds MySQL report, action-history
and visibility columns. The prototype still uses JPA auto-DDL. Plan
explicit Flyway/Liquibase migrations, backfill visibility for historical
reviews, and test with real data before deploying to an existing
database. Full integration and security checks are required before
merging this draft.

## Customer order history and order tracking (draft)

Authenticated customers can review their own single-product order history.
The existing `order:read` scope covers both new endpoints. The composite
service derives the owner from the verified user's JWT issuer and subject,
never a user-controlled query parameter. Application-only client-credentials
tokens cannot browse personal orders.

```http
GET /product-composite/orders/history?page=0&size=20
GET /product-composite/orders/history?page=0&size=20&status=MOCK_PAID
GET /product-composite/orders/{orderId}/timeline
```

Order history is sorted newest-first by saved creation time, with a
stable order-ID tiebreaker. It is database-paginated, uses a maximum
page size of 50, and supports exact filters for the existing order
states (including `RESERVED`, `MOCK_PAID`, `CANCELLED`, `EXPIRED`
and payment states). Pagination reports total count and total pages.
Each order response includes its original price, quantity, currency,
payment status, and optional **mock** shipment and return summaries.

A timeline assembles only **persisted, observed** events: order created;
simulated payment started/completed; shipment milestones; and return
milestones. Timestamps are chronological, not estimated. Earlier order
state transitions such as cancellation and expiry have no separate
persisted change timestamp, so those transitions are **not fabricated**
as timeline events. Current state remains visible on the order record.

The order history API is read-only. It does not re-run a checkout,
reserve stock, capture a payment, initiate a shipment, or issue a refund.
The project's `MOCK_PAID`, `MOCK_CARRIER` and `MOCK_REFUNDED`
indicators are simulations, not proof of real-world purchases,
deliveries or money movement.

**Operational limits:** Multi-document reads across orders, shipments
and returns are not a point-in-time MongoDB snapshot; concurrent
updates can cause temporarily differing statuses. Deep offset paging
can become expensive at scale. For large datasets, add an index on
`ownerKey + createdAt + _id` (and an indexed variant with status),
keyset pagination, monitoring, and retention policies. Keep the
internal `/internal/order-history/**` routes private and review the
full Java 17 CI + Testcontainers suite before merging.

## Multi-item checkout and crash recovery (draft)

The checkout service now accepts **1–20 distinct products in one cart**
(quantities 1–99 per product). Items are sorted by product ID before
creating a durable order manifest. Each order stores authoritative
product name, unit price, quantity and per-line total at checkout time,
and a computed grand total in one currency. Currencies must match
across every product. Cart display prices never determine payment amounts.

```http
POST /product-composite/orders
Authorization: Bearer <signed-in-customer-order-write-token>
Idempotency-Key: unique-multi-checkout-1234

GET /product-composite/orders/{orderId}
GET /product-composite/orders/history
```

The existing mock-payment intent now uses the **sum of every stored line
total**; order history exposes the complete item list. Cancellation,
expiration, mock-payment failure and successful mock-payment confirmation
iterate over **every** product's reservation token. Mock returns calculate
their simulated refund from the entire order total. Purchase-review
eligibility checks every product in a mock-paid order, not just the first.

### Failure recovery, safety and limits

The order's entire item snapshot is persisted **before** stock is
decremented. Each product's `reservedOrderIds` token ensures that
repeated reservation and release attempts for the same order do not
double-decrement or double-increment its quantity. On a line-item
stock failure, the order is marked `CANCELLATION_PENDING` and any
already-held stock is returned. The existing worker retries unfinished
`PENDING`, cancellation, expiration, and simulated payment transitions.

**This is NOT an atomic multi-product order transaction.** The product
updates and order document live in separate Mongo records; simultaneous
checkout/cancellation workers, a crash during rollback, or a missing
product may leave inconsistent stock and require operator reconciliation.
The worker is best-effort. Before real payment or production multi-item
orders, implement durable reservation leases/ownership, transactional
outbox or a MongoDB transaction on a replica set, inventory auditing,
timeouts, distributed worker coordination and forced-crash/concurrency
tests.

Other limits: Orders are single-currency and capped at 20 distinct
products; one mock shipment and one mock return still represent the
**entire order**, not individual packages or item-level returns.
Existing legacy single-product orders remain readable through the
original scalar fields and a one-line compatibility projection. No
real payment, shipping or refund takes place.

## Reservation processing claims and recovery (draft)

Multi-item checkout now includes a durable intermediate `RESERVING`
state with a unique order-processing claim and a two-minute expiry.
The checkout handler atomically claims a `PENDING` order, or a
`RESERVING` order whose earlier claim has expired, before it writes
any product inventory. Simultaneous retries with the same idempotency
key return the existing in-progress order instead of starting separate
reservation loops.

Each product reservation still uses the order ID in
`reservedOrderIds` alongside a conditional atomic stock decrement.
The order can only become `RESERVED` if the same claim still owns
it and both the processing claim and order TTL are active.
The background recovery worker does not touch active processing claims.
When a claim expires it may retry the exact stored order manifest,
which safely skips product IDs already held by that order.

On an insufficient-stock error or an expired order deadline,
the current claim atomically marks the order as
`CANCELLATION_PENDING` before compensating any held lines.
Compensation is idempotent per product token, and the recovery worker
retries interrupted cancellation and payment-finalization operations.
Older `PENDING` order documents can be claimed without migration;
legacy order documents still project to a one-line manifest.

**Important concurrency boundary:** This is *not* a
multi-document MongoDB transaction, a globally fenced inventory
ledger, or a distributed exactly-once protocol. A stale process
can still race between checking its claim and updating a separate
product document. Delayed network operations, a product deleted
mid-flight, or failures longer than the claim TTL can require
manual reconciliation. Do not use it for real charges or make
production-grade stock consistency guarantees. The next hardening
step would use MongoDB replica-set transactions or a durable
reservation ledger with conditional fencing, plus transactional
outbox and crash/fault-injection testing.

Run the full Java 17/Testcontainers suite and review concurrent
checkout, cancellation, mock payment and worker-recovery behavior
before merging.

## Transactional inventory and read-only reconciliation (draft)

Checkout now executes the entire multi-line inventory reservation and the
order's final `RESERVED` update in one **reactive MongoDB transaction**.
Cancel/expiry/decline compensation and successful mock-payment finalization
likewise commit all involved product updates together with the final order
state. Transactions use snapshot reads and majority writes and **do not**
silently fall back to non-transactional stock updates.

### Required database deployment

MongoDB multi-document transactions need a replica set (or sharded
deployment). The default `docker-compose.yml` now starts MongoDB
6 in single-node replica-set mode, initializes `rs0` and waits for a
primary in its healthcheck. The product service uses
`mongodb://mongodb:27017/product-db?replicaSet=rs0`. Its Testcontainers
test fixture also uses an actual replica-set connection.

**Existing Docker data:** If your local `mongo-data` volume was
created by standalone MongoDB, plan a controlled migration/backup.
Confirm `rs.status()` and replica-set health rather than deleting the
volume or blindly reinitializing. A one-member replica set is intended
for **local development only**; production requires multiple voting
members, a tested backup/restore procedure, and a proper deployment plan.
The alternative Kafka/partitioned Compose files must also be upgraded
to replica-set-capable MongoDB before exercising transactional checkout.

### Safety properties and limitations

- A failed reservation of any product aborts all the in-transaction
  deductions and does not leave partially committed stock. The previously
  claimed order is separately moved to a recoverable cancellation state.
- A successful mock payment clears all product hold tokens without
  restocking; cancellation, expiry and mock decline restore held stock
  at most once, atomically with terminal order state.
- Existing `reservedOrderIds`, lease claims and recovery handling remain
  to support retrying older interrupted orders and idempotent events.
- MongoDB write conflicts may cause a request to fail rather than silently
  oversell. The caller can safely retry with the same idempotency key.
  Test failure injection and commit-ambiguity behavior before considering
  real-money operations.

### Read-only operator audit

An authorized fulfillment-operator token with `fulfillment:read` may call:

```http
GET /product-composite/fulfillment/inventory/reconciliation
Authorization: Bearer <fulfillment-operator-token>
```

The report checks up to **100 products** and **100 active orders** per
request for orphaned hold tokens, terminal-order holds, missing product
holds and invalid stock. It returns a `truncated` flag when the bounded
scan is incomplete. **It never modifies stock**. This audit is a
best-effort snapshot, not a globally consistent real-time reconciliation
or a full inventory ledger. Investigate flagged records manually before
any correction. For production scale, replace offset/bounded scans
with durable inventory movements, audited repair approvals, and alerting.

All payment, shipping and refund operations in this repository still use
**mock states** and do not transfer real money or goods.

## Observability and reproducible performance checks (draft)

This phase adds **bounded Micrometer business metrics**, private
Prometheus/Grafana monitoring, sampled Zipkin traces, operator alert
rules and a read-only load-test script. All payments, deliveries and
refunds remain simulated.

### Local monitoring stack

Set strong `GRAFANA_ADMIN_USER` and `GRAFANA_ADMIN_PASSWORD` values
in your locally ignored `.env` file. The default Docker Compose stack
starts Prometheus and Grafana alongside the existing Zipkin container.

```sh
docker compose config
docker compose up --build -d
docker compose ps
```

After a healthy startup, use these local-only addresses:

- Grafana: `http://localhost:3000` — dashboard **Commerce Backend Reliability**
- Prometheus: `http://localhost:9090`
- Zipkin: `http://localhost:9411`

Ports bind to **127.0.0.1**, not all network interfaces. Grafana
requires the credentials in `.env`. Product and composite metric
endpoints are scraped privately over the Docker `backend` network,
not through the public Gateway. Do **not** publish service actuator
ports or expose Prometheus/Grafana/Zipkin without proper ingress
authentication, transport protection and network policy.

### Metrics and alerts

`commerce_order_operations_total` and
`commerce_order_operation_duration_seconds` track checkout,
cancellation, mock-payment intent and recovery outcomes with a fixed
`operation` and `outcome` vocabulary (`success`, `error`,
`cancelled`). No JWT, order ID, product ID, exception message,
request parameter or user identity becomes a metrics label.

`commerce_recovery_failures_total` tracks failed recovery attempts;
`commerce_recovery_last_batch_size` shows the most recent worker scan.
`commerce_inventory_audit_runs_total` and
`commerce_inventory_audit_findings_total` count **observed operator
audit findings**, tagged by discrepancy category. Repeating an audit
can count the same finding again; this is not a gauge of unique
unresolved inventory errors.

See `monitoring/rules.yml` for Prometheus alert expressions. Alert
conditions detect checkout error ratios (with a minimum request count),
recovery failures, inventory discrepancies and missing product-service
metrics. Alerts appear in the Prometheus UI; **they do not send email,
SMS or PagerDuty notifications** until an Alertmanager or other
notification receiver is separately configured.

Micrometer tracing is sampled at 10% for product and composite
containers and sent to local Zipkin using the configured collector URL.
Sampling means that individual requests might not appear in Zipkin.
Enable sampling and span attributes carefully in production; do not
attach personal data or authentication tokens to traces.

### Reproducible read-only load test

Install k6 independently, start the stack and obtain a valid customer
OAuth token with `product:read`. Use a seeded product ID only if you
also want to measure a specific product read:

```sh
k6 run -e ACCESS_TOKEN="$ACCESS_TOKEN" \
  -e BASE_URL=http://localhost:8443 \
  -e VUS=5 -e DURATION=30s \
  scripts/load-test.js
```

The test sends authenticated **GET-only** catalog requests. It does
not create or mutate orders, reservations, payments, or inventory.
The example targets a p95 under 1 second and an HTTP error rate
under 5%, but **these are test thresholds, not measured claims**.
Record actual p50/p95 latency, achieved requests per second, service
CPU/memory, error rate and container sizes in a separate dated result
if publishing portfolio benchmarks. Do not load-test public third-party
systems or production without permission. Use the k6 run output and
Grafana/Zipkin to explain bottlenecks and recovery behavior.

### Limitations

Prometheus, Grafana and Zipkin are local-development integrations.
Metric cardinality is intentionally bounded, but the dashboard is
an operational starting point, not a full SRE program. CI validates
application code and unit tests; a Docker Compose smoke test and actual
k6 performance numbers must be run in the target environment before
claiming monitoring or performance SLAs.

## Durable consumer inboxes, replay safety and dead-letter operations (draft)

The asynchronous product, recommendation and legacy review consumers now
recognize a stable `eventId` and store a **processed-event receipt**
alongside each successful business mutation. Every newly constructed
event receives a UUID. An incoming event's fingerprint is calculated
from its type, key and business payload, excluding its creation time.

When a broker redelivers a **previously committed** event ID with an
identical fingerprint, the handler returns successfully without repeating
the create/update/delete. Reusing that ID for a different payload is
rejected rather than silently ignored. The receipt and business mutation
are committed atomically in their respective databases:

- Product: MongoDB replica-set transaction and
  `processed_product_events` collection.
- Recommendation: MongoDB replica-set transaction and
  `processed_recommendation_events` collection.
- Legacy review: MySQL JPA transaction and
  `processed_review_events` table.

The review inbox concerns **legacy event-created reviews** only; the
newer customer-owned review and moderation endpoints use their own
independent persistence and authentication paths.

The existing Config Server repository currently configures bounded
message-consumer retries (three attempts) and RabbitMQ
`autoBindDlq`/`republishToDlq` for each service. Kafka has an
`enableDlq` configuration. Confirm the actual active binder and broker
DLQ settings in your environment before relying on dead-letter
delivery. This phase deliberately propagates handler failures so that
the binder can retry or dead-letter them; it never acknowledges a
failed transaction as processed. Operators can inspect RabbitMQ
dead-letter queues in the private RabbitMQ management interface.

### Compatibility and safeguards

Historical events without an `eventId` still follow the previous
behavior, **without replay guarantees**. Treat event-ID rollout as a
migration; retain the processed-event collections/table for at least
the entire retry and replay horizon, and add controlled retention once
the system has a documented replay policy. Never reset an inbox while
unacknowledged messages may be redelivered.

The recommendation service now requires MongoDB replica-set support,
matching the product service, because its event inbox and business
mutation are transactional. Default Docker Compose supplies
`mongodb://mongodb:27017/recommendation-db?replicaSet=rs0`,
and its Testcontainers fixture uses a replica-set URI. Alternative
Kafka/partitioned Compose configurations need corresponding updates
before being used with transactional event consumers.

**This is consumer idempotency, not a publisher transactional outbox.**
Spring Cloud Stream's successful `StreamBridge.send()` call does not
guarantee durable broker confirmation or atomic publication across
product/review/recommendation destinations. A composite update may
still partially publish. A durable publisher outbox, consistent
aggregate-level saga/reconciliation, DLQ replay administration and
alerting remain future phases.

### Verification

The new tests cover repeated event IDs, changed-payload conflicts,
failed-write rollback of receipts, and stable JSON event identity.
Run `gradle clean build` including Testcontainers, inspect the actual
broker retry/DLQ configuration, and exercise a broker disconnect and
redelivery in an isolated development stack before merging this draft.
