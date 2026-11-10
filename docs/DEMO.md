# Portfolio demonstration — local runbook

**Goal:** record a short, honest demonstration of service boundaries, authenticated HTTP APIs, durable event intent, eventual reads, CI and a recoverable broker outage. This is a **runbook**, not a claim that a recording or full runtime test has already been completed.

## 1. Prerequisites and safety

Use a disposable local development stack, Docker Compose v2, Java 17, trusted Gradle 8.6, Bash, curl, and Postman (optional). The external [config-repo](https://github.com/kakarot987/config-repo) must match local credentials and service discovery settings.

```bash
cp .env.example .env
# Change all placeholder passwords and review the config-repo settings.
gradle clean build
docker compose config
docker compose up --build -d
docker compose ps
./scripts/smoke-test.sh
```

Do not print or record your `.env` file, access tokens, client secrets, mock webhook signing secrets or private order/customer data. Ensure no previously committed credentials remain valid. The default gateway is `http://localhost:8443` (**HTTP, not HTTPS**). Observe logs via `docker compose logs --tail=100 product-composite product rabbitmq`.

**Access token:** You need a **signed-in demo user** token (not a client-credentials app token) with `product:read`, `product:write`, `cart:read`, `cart:write`, `inventory:read`, `order:read` and `order:write` scopes. Obtain it using your configured OAuth2 **authorization-code** flow; password grant is not supported. Default Compose does **not** publish the auth server port to the host, so arrange an explicitly localhost-only development access route and ensure issuer/JWKS remain consistent. Never commit this token. For the optional inventory audit use a separate operator token with `fulfillment:read`.

## 2. Reproducible catalog + event demo

A sample aggregate is in [`samples/product-demo.json`](../samples/product-demo.json), containing one product, one recommendation, and one review. The script gives it a fresh numeric product ID and performs health, authorization, create-202, and eventually-consistent read checks.

```bash
ACCESS_TOKEN="your-signed-in-demo-token" bash scripts/demo-catalog.sh
```

For a repeatable chosen ID:

```bash
ACCESS_TOKEN="your-signed-in-demo-token" PRODUCT_ID=900001 bash scripts/demo-catalog.sh
```

The script fails if a previously used ID is already present or the broker/database stack cannot complete the flow. It exits nonzero on an unexpected status or after its bounded polling window. **202 means the event batch is persisted, not that downstream reads already see it.**

## 3. Postman workflow (optional, after login)

Import [`postman/Amazon-Clone-Backend.postman_collection.json`](../postman/Amazon-Clone-Backend.postman_collection.json). Set collection variables:

- `baseUrl` = `http://localhost:8443`
- `accessToken` = fresh signed-in user JWT; keep it local (do not export or commit it)
- `productId` = empty to generate a random ID before the create request; clear it between new demos
- `operatorToken` = optional independent `fulfillment:read` JWT

Run in this order: **Health** → **Unauthenticated catalog denial** → **Create aggregate** → **Read aggregate** (retry manually until 200) → **Search catalog** → **Inventory** → **Set cart item** → **View cart** → **Checkout** → **Get order**. Checkout stores `orderId` automatically from the JSON response. Clear `idempotencyKey` only when you deliberately start a **different** checkout attempt. Running checkout twice with the same key intentionally returns the prior attempt. The inventory audit is an optional operator-only request.

Do not run the entire collection unattended: the post-create read is eventually consistent, and checkout requires a signed-in user, an active stock reservation, and a correctly configured local state. A failed assertion can reflect missing setup rather than a code defect.

## 4. Failure-recovery demonstration (manual and opt-in)

**Only in disposable local development.** Do not run this on shared or production infrastructure. Perform these commands in separate terminal windows and **always restart RabbitMQ**, including if the demonstration is interrupted.

1. Start from a healthy stack. Show `docker compose ps` and an authenticated product read.
2. Stop the broker: `docker compose stop rabbitmq`.
3. In Postman, **clear `productId`** so the create request generates a new ID, then send **Create aggregate**. If the API and MongoDB remain responsive, the expected result is **202** from durable outbox storage; do **not** present a failed request as a successful recovery test. Do not expect an immediate downstream GET.
4. Inspect `docker compose logs --tail=100 product-composite` for publisher retries. The dispatcher holds a leased queue head and backs off, potentially blocking later events.
5. Restart the broker: `docker compose start rabbitmq`. Poll **Read aggregate** using that same ID and look for eventual 200 with the product and its review/recommendation content.
6. Show the GitHub Actions Java 17 run for the current PR and the repository's workflow; record the exact run URL and outcome instead of claiming local tests passed when they have not.
7. Explain how stable event IDs, consumer inboxes and leased retries allow replay without assuming exactly-once or distributed business completion.

**Recovery boundaries:** broker reconnection, service readiness and delivery depend on your local deployment. If it does not converge, capture logs, verify MongoDB `rs0` health, check credentials/bindings, and report the failure honestly. The code currently lacks an end-to-end saga/reconciliation controller for repairing permanently divergent product/recommendation/review aggregates.

## 5. Suggested 4–6 minute recording outline

| Segment | Show | Explain |
| --- | --- | --- |
| 0:00–0:45 | Architecture Mermaid diagram | Gateway/composite/core services; Mongo vs MySQL and async RabbitMQ |
| 0:45–1:30 | Compose health, denied unauthenticated call | Local-only development setup, OAuth2 scopes |
| 1:30–2:30 | Postman create `202`, then eventual `200` | Durable event intent ≠ synchronized downstream completion |
| 2:30–3:15 | Cart, inventory, checkout/order receipt | Product DB transactions, multi-line stock consistency, idempotency |
| 3:15–4:30 | Broker outage, restart and replay | At-least-once, outbox lease, deduplicating consumers |
| 4:30–5:30 | CI run and constraints | Testcontainers, mock payments, auth limitations, planned saga recovery |

Before publishing: remove sensitive values from terminal history and screenshots; ensure the demo actually works in your environment; link the **actual** recorded video and CI run in the README only after they exist. No deployment, recording, real payments, or real shipments are claimed by this documentation PR.

## Optional aggregate reconciliation check

With a separate operator token holding `fulfillment:read`, request `GET /product-composite/fulfillment/aggregates/{productId}/reconciliation` for the same demo product. During broker interruption the diagnostic may report `PUBLISH_PENDING`; after recovery rerun and inspect `OBSERVED`, `CONVERGING` or a failure state rather than assuming success. The check compares tracked IDs only and is **not** saga completion. See [status details](RECONCILIATION.md).

## Optional operation journal demo

With a `fulfillment:read` operator JWT, call `GET /product-composite/fulfillment/aggregates/{productId}/operations?limit=10` after creating the demo product. Select the latest operation ID and inspect `GET /product-composite/fulfillment/operations/{operationId}`. Show that the event IDs and publication states are durable; during broker failure the result may be `PUBLISH_PENDING` or `DISPATCHING`, and after recovery `BROKER_ACCEPTED`. The `consumerStatus` field stays `NOT_VERIFIED`. These are observed runtime outcomes, not guaranteed demo timestamps or proof of business convergence. See [operations guide](OPERATIONS.md).

## Optional verified consumer receipt demo

With the operator JWT, call `GET /product-composite/fulfillment/operations/{operationId}/receipts` after examining a tracked operation. The endpoint uses a short-lived HMAC request to ask internal consumers whether their transactional inbox includes the original event ID and matching fingerprint. Observe `PUBLISH_PENDING`, `RECEIPTS_MISSING`, `INCONCLUSIVE`, `CONFLICT`, or `ALL_RECEIPTS_VERIFIED`; never claim success without inspecting the actual response. No recovery mutations are performed. See [receipt evidence guide](RECEIPTS.md).

## Optional fenced-supervisor demonstration

By default `APP_SAGA_ENABLED=false`, so new operation supervision records remain `PENDING`. To demonstrate transitions on **disposable** local infrastructure, configure a fresh 32+ byte `APP_RECEIPT_PROBE_SECRET` on all four services, explicitly set `APP_SAGA_ENABLED=true`, and restart `product-composite`. After a sample aggregate create, inspect `GET /product-composite/fulfillment/operations/{operationId}/supervision` with an operator token. Show the actual `WAITING`, `RECEIPTS_VERIFIED`, or `NEEDS_REVIEW` state; never presume full saga completion. Return to `APP_SAGA_ENABLED=false` after recording. See [supervision runbook](SAGA_SUPERVISION.md).

## Optional incident triage demo

Use the operator read endpoint `GET /product-composite/fulfillment/supervisions/review-queue?limit=20` to show durable operations flagged `NEEDS_REVIEW`. For such an operation only, an operator with a `fulfillment:write` JWT may add a synthetic incident note using a fresh `Idempotency-Key`, and list the note history via `GET /product-composite/fulfillment/operations/{operationId}/triage`. Sending the same key/content twice is idempotent; changing content under the same key returns 409. Do not enter tokens, secrets, customer data or real names into notes. The demonstration never replays or compensates events. See [triage runbook](SAGA_TRIAGE.md).

## Optional recovery operator controls demo

With an operator `fulfillment:read` JWT, call `GET /product-composite/fulfillment/recovery/overview` to inspect supervision counters and the oldest undelivered outbox event (no payload). If a disposable operation is `NEEDS_REVIEW`, an operator with `fulfillment:write` may make one **manual read-only receipt recheck request** using `POST /product-composite/fulfillment/operations/{operationId}/rechecks` with a fresh `Idempotency-Key` and `{ "reason": "CONSUMER_RESTORED" }`. The server records the request atomically with resetting its local diagnostic supervision to `PENDING`; it does not send events. With `APP_SAGA_ENABLED=false` it stays pending. Observe history at `GET /product-composite/fulfillment/operations/{operationId}/rechecks`. See [recovery controls](RECOVERY_CONTROLS.md).
