# Durable aggregate operation journal (saga foundation)

**This is operation tracking, not automatic saga recovery.** The composite service now persists one immutable-intent `composite_operations` document **in the same MongoDB transaction** as every outgoing `composite_outbox` batch. The journal groups the original stable event IDs, target bindings, product IDs and event types. It never contains the serialized customer/review payload.

The operation record is retained for operator correlation and cross-service recovery design. `StreamBridge.send` accepting a message is not a broker durability acknowledgement. Even `BROKER_ACCEPTED` **does not mean any consumer committed a business change**.

## Operator read endpoints

Both endpoints require `SCOPE_fulfillment:read`, not a customer JWT. Responses include original event IDs for correlation; use operator-only logs and do not expose these endpoints publicly.

```http
GET /product-composite/fulfillment/aggregates/900001/operations?limit=10
Authorization: Bearer <fulfillment:read operator JWT>
```

Lists newest operations touching the product ID. `limit` must be between 1 and 20.

```http
GET /product-composite/fulfillment/operations/{operationId}
Authorization: Bearer <fulfillment:read operator JWT>
```

Looks up a single UUID operation. Unknown IDs return 404; invalid IDs return 400.

Example shape (illustrative, not runtime output):

```json
{
  "operationId": "11111111-2222-3333-4444-555555555555",
  "createdAt": "2026-11-10T13:00:00Z",
  "productIds": [900001],
  "publicationStatus": "PUBLISH_PENDING",
  "consumerStatus": "NOT_VERIFIED",
  "expectedEvents": 2,
  "publishedEvents": 1,
  "pendingEvents": 1,
  "inFlightEvents": 0,
  "events": [
    {
      "eventId": "event-a000",
      "bindingName": "products-out-0",
      "eventType": "CREATE",
      "productId": 900001,
      "state": "PUBLISHED",
      "attempts": 1,
      "nextAttemptAt": "2026-11-10T13:00:00Z",
      "publishedAt": "2026-11-10T13:00:01Z"
    },
    {
      "eventId": "event-b000",
      "bindingName": "reviews-out-0",
      "eventType": "CREATE",
      "productId": 900001,
      "state": "PENDING",
      "attempts": 2,
      "nextAttemptAt": "2026-11-10T13:01:00Z",
      "publishedAt": null
    }
  ]
}
```

## Derived publication statuses

| Status | Meaning |
| --- | --- |
| `PUBLISH_PENDING` | One or more of this operation's events are not marked published; dispatcher may retry later. |
| `DISPATCHING` | An event currently holds an in-flight publishing lease. The lease may expire after a crash. |
| `BROKER_ACCEPTED` | All events are marked `PUBLISHED` after StreamBridge accepted them. **No consumer acknowledgements exist.** |
| `INCOMPLETE_JOURNAL` | The operation or an associated event is missing/mismatched; no completion claim. |
| `UNKNOWN_STATE` | An unrecognized outbox state prevents a conclusion. |

`consumerStatus` is always `NOT_VERIFIED` in this phase, including when every event is marked `PUBLISHED`. This deliberately avoids conflating transport-level acceptance with distributed business completion.

## Atomicity and limitations

- The operation and every event are committed or rolled back together in the **composite** MongoDB replica-set transaction. Duplicate event IDs cause a transaction rollback without leaving a phantom operation. The existing 100-event batch limit is unchanged.
- Each enqueue operation gets a new UUID; this **does not provide request-level idempotency** for repeated HTTP POST/DELETE calls with new event IDs.
- Historical outbox entries written before this phase have no `operationId` and remain deliverable. They do not retroactively appear in the new operation list. The [read-only aggregate reconciliation endpoint](RECONCILIATION.md) can still inspect their recent event history.
- Outbox delivery remains at least once. `BROKER_ACCEPTED` is not a broker publisher confirm, consumer acknowledgement, saga completion, data convergence, or payment settlement.
- Operation documents grow with every write. Backups, retention and database indexes need an explicit operational policy. The entity declares an index on product IDs + creation time; verify the physical index exists, since automatic Mongo index creation may be disabled.
- An operation can involve multiple product IDs if one internal enqueue contains multiple keys; listing by any of those products finds the same operation. No partial completion is marked successful.
- The read APIs never alter state or trigger compensation. There is **no new operator replay endpoint** in this milestone.

## Next step: explicit consumer receipts

For true saga state management, introduce an authenticated or signed, idempotent **consumer outcome protocol** keyed by stable `eventId` and target binding, persisted transactionally with each consumer's business mutation. The composite coordinator must reject unknown event IDs, conflicting receipts and stale completion signals, then represent terminal success/failure, quarantine and compensation with separately audited policies. Use conditional writes or fenced leases for competing coordinators and test process crash, replay, poison records and consumer unavailability before allowing any compensation. Do not infer success from reading a product alone.

## Signed consumer inbox evidence

An operator can request `GET /product-composite/fulfillment/operations/{operationId}/receipts` with `fulfillment:read` to inspect transactionally committed consumer inbox records through signed internal-only HTTP probes. An outcome of `ALL_RECEIPTS_VERIFIED` is evidence that every tracked event was once applied at its destination, **not** a globally consistent current aggregate or completed saga. The feature requires a unique `APP_RECEIPT_PROBE_SECRET` shared by the four data services; without it the audit returns 503 and consumer probe endpoints fail closed. See [consumer receipts](RECEIPTS.md).

## Opt-in durable supervision

Every new operation now includes a `PENDING` record in `aggregate_supervisions`, written atomically with the operation and outgoing events. The disabled-by-default background supervisor uses MongoDB atomic lease claims, generation fencing, bounded checks and read-only signed receipt observations. Operators inspect `GET /product-composite/fulfillment/operations/{operationId}/supervision` with `fulfillment:read`; no automatic replay/compensation is performed. See [supervision runbook](SAGA_SUPERVISION.md).
