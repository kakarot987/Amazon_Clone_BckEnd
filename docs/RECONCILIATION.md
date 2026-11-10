# Distributed aggregate reconciliation — first recovery milestone

The composite service exposes a **read-only** operator diagnostic for one product ID:

```http
GET /product-composite/fulfillment/aggregates/{productId}/reconciliation
Authorization: Bearer <fulfillment:read operator JWT>
```

This endpoint is protected by `SCOPE_fulfillment:read`. It is **not** customer-facing. It does not repair, republish, delete, compensate or mark any outbox record successful.

## What it does

1. Reads the newest **101** `composite_outbox` entries for a positive product `partitionKey`, returning `INCOMPLETE_HISTORY` if there are more than 100 records.
2. Derives expected IDs in oldest-to-newest order from bounded journal entries: product CREATE/UPDATE means present; product DELETE means absent. Child CREATE/UPDATE adds review or recommendation IDs, and DELETE clears those expected IDs.
3. Waits until all tracked destination events reach `PUBLISHED` before probing that destination. `PUBLISHED` is **StreamBridge acceptance, not a durable broker confirmation**.
4. Independently reads product, recommendation and review HTTP APIs. A product HTTP 404 means absent; failures and non-successful list responses mean `UNAVAILABLE`, not missing.
5. Compares observed **ID sets only**. Differences within a two-minute settling window are `CONVERGING`; older differences are `DRIFT_CANDIDATE` for human investigation.

No payload values, pricing, stock, customer or payment state is compared. The independent reads are **not globally snapshot-consistent**.

## Example response shape

```json
{
  "productId": 900001,
  "checkedAt": "2026-11-10T12:00:00Z",
  "status": "DRIFT_CANDIDATE",
  "truncated": false,
  "eventsScanned": 3,
  "checks": [
    {"destination":"products-out-0","journal":"PUBLISHED_ACCEPTED","observation":"MATCHED","expectedItems":1,"observedItems":1,"note":"Matching read-only snapshot"},
    {"destination":"recommendations-out-0","journal":"PUBLISHED_ACCEPTED","observation":"MATCHED","expectedItems":1,"observedItems":1,"note":"Matching read-only snapshot"},
    {"destination":"reviews-out-0","journal":"PUBLISHED_ACCEPTED","observation":"DRIFT_CANDIDATE","expectedItems":1,"observedItems":0,"note":"Observed IDs differ from latest tracked intent"}
  ]
}
```

The data above are illustrative, not a recorded incident.

## Status meanings

| Status | Interpretation |
| --- | --- |
| `NOT_TRACKED` | No matching outbox entries; no completeness claim. |
| `INCOMPLETE_HISTORY` | More than 100 events; do not infer expected state from a partial window. |
| `INVALID_JOURNAL` | Invalid event payload/destination; no downstream probes. |
| `PUBLISH_PENDING` | At least one event is pending or leased; no downstream failure inference. |
| `CONVERGING` | IDs differ but publication is recent or has no reliable timestamp. |
| `INCONCLUSIVE` | One or more downstream services could not be verified. |
| `DRIFT_CANDIDATE` | Snapshot ID mismatch after settling period; investigate, not necessarily permanent failure. |
| `OBSERVED` | All **tracked** destinations matched the expected ID sets at observation time. Not a saga completion receipt. |

Destinations without journal entries are marked `NOT_TRACKED` / `NOT_CHECKED` and excluded from comparisons. Concurrent writes, external changes, untracked data, and payload differences are not covered.

## Operator workflow

- Confirm broker and services are healthy, and authenticate with an independent `fulfillment:read` operator JWT.
- Inspect a known product after create/delete. Retry later for `PUBLISH_PENDING` or `CONVERGING`.
- For `INCONCLUSIVE`, investigate service connectivity and logs instead of changing data.
- For `DRIFT_CANDIDATE`, correlate original event IDs and consumer inbox/DLQ records before deciding on a repair. Never replay with new IDs or edit business data without an approved procedure.
- For `INCOMPLETE_HISTORY`, use an audited paginated ledger query rather than treating the latest 100 entries as the full history.
- Index the journal for scale: `db.composite_outbox.createIndex({partitionKey:1,createdAt:-1,_id:-1},{name:"outbox_reconcile_idx"})` in the composite Mongo database under an approved migration. The entity includes index metadata, but verify the index exists in deployed environments; a result limit does not avoid a full scan without an index.

## Remaining saga work

This first slice is deliberately **non-mutating**. Next steps are durable aggregate operation IDs, acknowledgement tracking across services, explicit timeout/compensation policies, quarantined and audited replay tools, and concurrency/fault-injection tests. Nothing here guarantees exactly-once delivery, global consistency or real financial settlement.
