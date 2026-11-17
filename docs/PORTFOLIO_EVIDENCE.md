# Client-facing portfolio demo evidence

This checklist is a template for a **future verified local demo**, not a record that a live run has already happened. Fill the evidence column only after personally executing it.

| Step | What to demonstrate | Evidence to collect | Status |
| --- | --- | --- | --- |
| 1 | Build and start disposable Docker Compose environment | CLI output showing healthy services, sanitized compose status | Not recorded |
| 2 | Anonymous API denied; authenticated product create accepted as 202 | Redacted HTTP status / request ID | Not recorded |
| 3 | Product, review and recommendation converge after event fan-out | Script's three-ID success checks | Not recorded |
| 4 | Optional mock cart and checkout idempotency | Same order ID for repeated idempotency key, no real payment | Not recorded |
| 5 | Optional operator read-only receipt evidence | Summary of three signed inbox receipts (redact tokens) | Not recorded |
| 6 | Stop/restart RabbitMQ and observe recovery | Timestamped sanitized backlog/eventual convergence | Not recorded |
| 7 | Record one controlled performance test if desired | Hardware, test setup, run command, measured result and limitations | Not measured |

## Local commands

With `.env` populated safely and services configured as in [DEMO.md](DEMO.md):

```bash
docker compose config
docker compose up --build -d
docker compose ps
./scripts/smoke-test.sh
python3 scripts/verify-portfolio-demo.py --execute
# Optional mock checkout after obtaining the required local token scopes:
python3 scripts/verify-portfolio-demo.py --execute --checkout
```

Only run the script with your disposable local gateway and your own access token set in the local environment. Do not publish tokens, secrets, actual customer data, or screenshots of private config.

## Engineering caveats to speak about in interviews

- Outbox dispatch and consumer inboxes help with retries/duplicates; they are not proof of exactly-once global transactions.
- `StreamBridge.send()` acceptance is not RabbitMQ publisher confirmation. Publisher confirms are a possible further enhancement.
- The application simulates payments, shipping and refunds. External provider integrations are out of scope.
- Identity persistence, TLS hardening, production scalability, recovery compensation and deterministic fault-injection evidence are incomplete.
- GitHub CI verifies a bounded test surface, not a fully booted, manually fault-injected distributed stack.

Include a video link in the README **only after** an actual local screen recording exists.
