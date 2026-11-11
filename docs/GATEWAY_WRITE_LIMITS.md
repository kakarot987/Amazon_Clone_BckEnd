# Gateway write throttling (single instance)

The gateway enforces a **best-effort, fixed one-minute window** on authenticated writes to `/product-composite` and its child paths. The default quota is **60 writes per authenticated issuer+subject per minute**. Supported methods are POST, PUT, PATCH and DELETE. Catalog reads, health probes and the mock webhook (which authenticates its own HMAC signature) are excluded. Exceeding the quota returns HTTP 429 with `Retry-After: 60`.

## Configuration

Set `GATEWAY_WRITE_RATE_LIMIT_PER_MINUTE` and `GATEWAY_WRITE_RATE_LIMIT_MAX_CALLERS` in a private local environment or `.env` file and restart the gateway. The defaults are 60 and 10,000. The limit applies per authenticated JWT subject; `client_credentials` tokens use their client identity and so share a quota among callers using the same service token.

The limiter uses local process memory, stores only issuer+subject keys (which still may be sensitive identifiers), and bounds the number of tracked callers. Old windows are evicted when capacity is reached. It does **not** trust unverified `X-Forwarded-For` headers or IP addresses for identity. The downstream Spring Security scope checks remain authoritative for all protected API endpoints.

## Explicit limitations

This is **not** distributed rate limiting, an abuse-protection platform, an authorization mechanism, or a substitute for an upstream WAF. The counters reset on gateway restart and are not shared across replicas; non-JWT principals are passed through for the existing security chain to enforce authentication. At capacity, the limiter can return 429 for otherwise authenticated new callers. For production-scale traffic, replace this with a central Redis-backed limiter or properly managed gateway/WAF policy, including trusted proxy handling, concurrency tests, metrics, and alerting.

Tests cover route selection (including the aggregate root), quota reset, isolation by caller, and bounded storage. The local Compose demonstration is pending.
