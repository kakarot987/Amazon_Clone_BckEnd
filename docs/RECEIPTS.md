# Signed consumer receipt evidence

This **read-only** milestone checks whether the product, recommendation and review consumers persisted the original event ID/fingerprint in their existing transactional inboxes. It does not implement compensation or automatic saga coordination.

## Operator route

```http
GET /product-composite/fulfillment/operations/{operationId}/receipts
Authorization: Bearer <fulfillment:read JWT>
```

The composite service first reads the durable operation and the exact associated outbox events. It rejects an incomplete journal, and for events marked `PUBLISHED` sends an authenticated read request to the corresponding internal consumer. The consumer inbox evidence was written in the **same local transaction** as that consumer's business mutation. The request includes a timestamp and a SHA-256 HMAC bound to GET, the expected destination, and the original event ID.

The composite service compares the returned ID and committed fingerprint to the fingerprint reconstructed from the original outbox event. Each probe has a three-second timeout; a network failure or unauthorized probe yields `UNAVAILABLE`, **not** a missing receipt. A 404 from an authenticated, reachable consumer means `MISSING`.

## Configuration and deployment

Generate a fresh shared secret of at least 32 random UTF-8 bytes and place it in a local, uncommitted `.env` file as `APP_RECEIPT_PROBE_SECRET`. The default Compose file passes this to **only** the composite and the three consumer services. The feature fails closed (503) if not configured. Rotate this value through a secure secret manager in a real deployment, and restart all four services together during rotation.

Keep all `/internal/**` consumer endpoints unreachable from public clients and any externally reachable reverse proxy. The HMAC is useful defense-in-depth for **read-only** probes, not a replacement for TLS, network segmentation, service identity, durable broker acknowledgements or an authorization server. Synchronize clocks within 60 seconds; signed reads are replayable within the brief timestamp window but cannot mutate state. Do not log the secret/signatures or include them in screenshots.

The default Compose gateway forwards only its catalog/composite routes; internal consumer ports are not published to the host. Other deployment variants must explicitly add matching private configuration before attempting these probes.

## Outcome vocabulary

| Audit status | Meaning |
| --- | --- |
| `ALL_RECEIPTS_VERIFIED` | Every event in this tracked operation has an inbox record with exactly the original fingerprint; this proves **historical local commits**, not current global agreement. |
| `PUBLISH_PENDING` | At least one outbox event is still pending or leased. |
| `RECEIPTS_MISSING` | Some authenticated reads returned 404; the consumer may simply be behind or processing a retry. |
| `INCONCLUSIVE` | A consumer failed or timed out; absence cannot be inferred. |
| `CONFLICT` | A receipt conflicts with the expected fingerprint/ID or the original event is malformed. |
| `INCOMPLETE_JOURNAL` | Operation/outbox correlation cannot be trusted, so no probes are attempted. |

Per-event statuses are `VERIFIED`, `MISSING`, `UNAVAILABLE`, `PUBLISH_PENDING`, `CONFLICT` or `INVALID_EVENT`. This API intentionally never reports compensated, completed, paid or delivered.

Older events preceding the durable operation journal do not appear in this audit. A product/review may be altered later by another legitimate event, even after a verified receipt; use [aggregate reconciliation](RECONCILIATION.md) to inspect current snapshots separately.

## Next recovery phase

Build a durable coordinator with explicit approved policies for operation deadlines, terminal success, quarantine and compensation. Any redelivery or repair must preserve the original event ID and validate consumer state first; stale workers require fenced control. Include fault injection and crash tests before enabling corrective writes.
