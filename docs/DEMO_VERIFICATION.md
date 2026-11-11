# Verified portfolio demo contract

This phase makes the existing portfolio walkthrough **reproducible and testable**, with a strict distinction between synthetic mock-server tests, live local APIs and real broker failure recovery.

## Before running

Use a **disposable** Docker Compose environment with the correct external config repository. Never point demo tooling at production or a shared development database. The command is restricted to an HTTP gateway on `localhost` or `127.0.0.1`; its `--execute` flag is mandatory because catalog creation mutates local state.

Obtain a **signed-in user** access token with `product:read`, `product:write` and, for checkout, `cart:write` and `order:read` / `order:write`. The default auth service uses a local demo account; client-credentials tokens do not represent customers. For operator receipt checks, obtain a **separate** JWT with `fulfillment:read`. Configure the shared receipt-probe secret on the four internal services, as documented in [signed receipts](RECEIPTS.md). Do not paste secrets into PRs, Actions logs or screenshots.

The scripts intentionally do **not** obtain/refresh tokens automatically, expose new ports, change Compose networking or create real payments.

## Live scenarios (run locally)

Start from a healthy stack, then export `ACCESS_TOKEN` and optionally `OPERATOR_TOKEN` **locally**. Keep tokens out of command-line arguments and shell history.

**1. Full fan-out, synthetic catalog only:**

```bash
python3 scripts/verify-portfolio-demo.py --execute
```

The script checks gateway health (`UP`), verifies anonymous catalog reads are denied (401 or 403), creates a fresh random positive product ID with one review and recommendation, verifies creation returns **202 durable intent**, then polls until **all three** ID sets are visible (not merely product HTTP 200).

**2. Include a synthetic mock checkout:**

```bash
python3 scripts/verify-portfolio-demo.py --execute --checkout
```

After successful catalog convergence it adds one unit to the signed-in user's cart, checks the returned cart contents, reserves a **mock** order using a fresh `Idempotency-Key`, repeats the same checkout request, verifies the **same orderId**, and reads the order. This does **not** charge real money or settle a payment.

**3. Include operator receipts:**

```bash
python3 scripts/verify-portfolio-demo.py --execute --checkout --operator
```

The script lists operations for the new product and checks the signed internal inbox evidence of its original three events. It permits temporary `PUBLISH_PENDING`, `RECEIPTS_MISSING` or `INCONCLUSIVE` responses within the configured polling budget, but fails on `CONFLICT`, `INCOMPLETE_JOURNAL`, unexpected HTTP status, incorrect counts or eventual timeout. `ALL_RECEIPTS_VERIFIED` means historical per-service commits, **not** a fully compensated saga or globally atomic snapshot.

The default poll budget is 40 attempts every three seconds. For a slower local machine: `--attempts 80 --interval 3`. Use `--product-id` only for a **fresh, unused** 32-bit positive integer. Failure exits nonzero and reports only status/category, not response bodies containing sensitive data. The script does not print or persist token values.

## Manual broker interruption drill

Use the existing [disposable-stack walkthrough](DEMO.md). Start the healthy stack first, then `docker compose stop rabbitmq`, submit a fresh synthetic create using the script or Postman (the demo script will return failure if the broker cannot recover within its poll budget), and **restore RabbitMQ** with `docker compose start rabbitmq` in a separate terminal. Recheck the same product ID and operator event receipts, monitoring Grafana/Prometheus outbox backlog. Keep your broker restore command prepared before stopping it.

**Do not claim CI ran this drill.** The GitHub-hosted Java/Testcontainers build and Python offline suite do not boot the entire multi-service Compose graph or intentionally interrupt a broker on behalf of the user. The actual results must be recorded from your local run. Do not use a real checkout account or publish private logs.

## Offline CI contract tests

```bash
python3 -m unittest discover -s scripts -p test_portfolio_demo.py -v
```

These tests replace HTTP calls with in-process responses and verify localhost-only URL validation, explicit execute consent, bounded polling, partial-delivery rejection, checkout idempotency, operator receipt counting, and a complete synthetic sequence. **No Docker, OAuth2 service, database or network access is required.** CI runs them alongside the existing Java 17 build and Testcontainers integration suite.

## Acceptance checklist

- Latest main branch **Java CI passes** for the actual merged commit.
- Local Docker Compose starts successfully (gateway, Mongo replica set, MySQL, RabbitMQ and all consumers).
- Signed-in token scopes allow catalog/cart/checkout; anonymous request is rejected.
- Catalog returns **202**, then product + review + recommendation all converge.
- Optional same-key checkout returns **one identical order ID** without real payment.
- Optional operator receipt audit verifies exactly the three synthetic event IDs.
- Manual RabbitMQ outage/recovery is demonstrated only when actually performed and the broker is restored.
- Recording/screenshots contain **no credentials or private customer data**, and distinguish simulated payments, local recovery checks, and features still missing.

Only after completing the live steps should the README link to an actual recording. There is no recorded video, externally deployed service, real payment provider or claimed production SLO in this PR.
