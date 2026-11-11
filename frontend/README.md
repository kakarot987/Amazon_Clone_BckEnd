# Cartlane — lightweight storefront

A responsive, **zero-runtime-dependency** storefront for the existing Java microservices. It is a portfolio demo, **not a real-money shop**.

## One-command startup

Prerequisites: Docker Engine and Docker Compose v2, plus credentials/config matching the external [config-repo](https://github.com/kakarot987/config-repo).

1. Copy `.env.example` to `.env` and replace all placeholder secrets with fresh development-only values.
2. From repository root run:

```bash
cp .env.example .env
# Edit .env securely, then:
bash scripts/start-storefront.sh
```

3. Visit **http://localhost:8088**. Gateway: **http://localhost:8443** (HTTP, not HTTPS).

| Task | Command |
| --- | --- |
| Start/build backend + frontend | `bash scripts/start-storefront.sh up` |
| Show containers | `bash scripts/start-storefront.sh status` |
| Follow storefront/gateway logs | `bash scripts/start-storefront.sh logs` |
| Stop combined stack (keeps volumes) | `bash scripts/start-storefront.sh down` |

Alternatively run `docker compose -f docker-compose.yml -f docker-compose-frontend.yml up --build -d`. This override adds **only** nginx/static frontend. It does not alter backend credentials, routes or databases. All host-facing storefront ports bind to localhost. If the API is offline, check the existing Compose stack and config-repo.

## Authentication

The backend requires OAuth2/JWT scopes. The existing auth server is not published to the host by default. Obtain a short-lived, **signed-in demo user** JWT using your separately configured local OAuth2 authorization-code flow, click the session control in the UI, and paste it. A client-credentials token is **not** a customer token for carts/orders. The optional operations tab needs a **separate** `fulfillment:read` operator JWT.

Tokens remain **in page memory**, never in localStorage, cookies, URL query parameters or repository files. A page reload clears them. This is a developer-only token-connect interface, **not an OAuth2 sign-in/signup implementation**. In production, use Authorization Code + PKCE, HTTPS and reviewed session handling.

The frontend serves the same origin for API calls via nginx `/api/` → internal gateway. No broad CORS settings or direct access to Mongo/RabbitMQ/MySQL services are needed.

## Screens

- **Discover**: paged, searchable, sortable catalog with product details and asynchronously delivered reviews/recommendations.
- **Publish synthetic product**: requires `product:write`; HTTP 202 is accepted intent, **not** immediate completion.
- **Wishlist**: owner-scoped saved products.
- **Bag**: owner-scoped cart and quantity edits; mock checkout with an idempotency key retained after a failed request.
- **Orders**: order history and receipt, explicitly labeled as simulated payment and shipping.
- **System status**: read-only outbox/saga summaries and manual review queue with the separate operator token.

All server text is escaped, no remote image dependencies are required, and friendly API errors do not reflect credentials or raw backend responses.

## Testing

Runtime is plain nginx; no npm install is needed. For local source checks install Node.js 20+:

```bash
cd frontend
npm test
npm run check
```

GitHub Actions checks Node syntax, the API adapter, and frontend nginx/Compose contracts in addition to the Java 17 tests. **CI static tests do not run the full local Compose environment**.

## Limitations

- An external config-repo must provide compatible auth/discovery configuration.
- No real payment, shipping, signup, password recovery, or integrated OAuth2 login flow.
- The local ports use **HTTP**: never expose them to public networks or paste real-account tokens.
- The catalog is eventually consistent after asynchronous publish; refresh after HTTP 202.
- System status is read-only; no event replay/compensation controls are exposed.
- A local live run is still required before claiming a portfolio demo recording or end-to-end deployment success.
