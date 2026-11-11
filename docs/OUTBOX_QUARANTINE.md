# Publisher poison quarantine and operator redrive

This phase adds **application-level quarantine** for the product-composite transactional outbox. It is not a broker dead-letter queue (DLQ), and it is **not** an automatic saga compensation engine.

## Activation

The existing behavior is unchanged by default. In local `.env`, opt in explicitly:

```dotenv
APP_OUTBOX_QUARANTINE_ENABLED=true
APP_OUTBOX_QUARANTINE_MAX_ATTEMPTS=8
```

The default Compose file passes these values to **only** product-composite. Keep the feature disabled until CI and a disposable local recovery drill have been reviewed.

## Invariants

- The dispatcher reads the **oldest unfinished event**, ordered by creation time and stable ID. Creation times in a new multi-event batch are separated by **one millisecond**, because MongoDB dates cannot preserve nanosecond ordering.
- With quarantine enabled, a broker send returning false or throwing repeatedly moves the original event to `QUARANTINED` after the configured attempt budget. The entry retains its exact original event ID, destination, partition key and serialized payload; it stores the quarantine timestamp and a generic failure class (`BROKER_REJECTED` or `PUBLISH_EXCEPTION`).
- A quarantined event is **still the global queue head**. The dispatcher stops, rather than letting later events overtake it, preserving strict cross-destination order. A single poison event therefore blocks all later publications until a human examines it.
- Publisher `PUBLISHED` continues to mean StreamBridge acceptance only, **not** durable broker confirmation or transactional consumer processing. Use signed consumer receipts for historical local commits.
- Expired lease owners cannot change state: all publish/retry/quarantine writes require the exact in-flight lease ID **and an unexpired lease**. Ambiguous sends may occur more than once; downstream inboxes deduplicate original IDs.

## Operator endpoints

```http
GET /product-composite/fulfillment/outbox/{eventId}/redrives?limit=10
Authorization: Bearer <fulfillment:read operator JWT>
```

Returns up to 20 immutable, newest-first redrive request records for a known outbox event. It exposes only event ID, hashed operator reference, allowlisted reason, payload SHA-256 digest, attempt sequence and timestamp — **not** message contents, lease tokens, or raw identity claims.

```http
POST /product-composite/fulfillment/outbox/{eventId}/redrives
Authorization: Bearer <fulfillment:write operator JWT>
Idempotency-Key: example-operator-retry-0001
Content-Type: application/json

{"reason":"BROKER_RESTORED"}
```

Allowed reasons: `BROKER_RESTORED`, `CONSUMER_RESTORED`, `OPERATOR_APPROVED`. Requirements:

- Quarantine/redrive mode must be enabled. If disabled, the write endpoint returns HTTP 503 without modifying state.
- Only the **currently quarantined global head** may be redriven. The original JSON must deserialize into a valid tracked event with matching event ID, partition key and allowlisted destination. Malformed/unknown/non-head entries return an error.
- At most **three unique manual redrives per event**. Exhaustion returns HTTP 409; manual intervention beyond this limit needs an independently reviewed repair procedure.
- The server hashes operation event ID + idempotency key into an immutable request ID. Exact retries by the same JWT issuer/subject and reason return the **original intent**, even after the event has already been reopened. Key reuse with a different operator or reason returns 409.
- The original event's `QUARANTINED → PENDING` transition, retry count reset, and immutable audit intent insertion occur in **one MongoDB transaction**, with conditional state, quarantine timestamp and attempt-count comparisons. Concurrent operators cannot manufacture two successful reopenings.
- Reopening the original outbox record permits the **existing** dispatcher to attempt that exact message again. It does **not** insert a new outbox record, change an event ID, edit a consumer inbox, modify an order/stock/payment/return, or invoke business compensation.

## Operator response to a poison event

1. Check the read-only [recovery overview](RECOVERY_CONTROLS.md) and Prometheus series `commerce_reliability_outbox_quarantined`. Identify the oldest blocked event using its ID, without logging the payload.
2. Inspect the existing aggregate operation journal, signed consumer receipts, and service/broker logs. Resolve broker/configuration/consumer problems first. A consumer may already have processed an event whose publisher acknowledgement was ambiguous.
3. Add an investigation note when the operation is eligible; approve one idempotent redrive of the original event ID and monitor its progress. Restore the broker before running the publisher.
4. If the event repeatedly quarantines or receipt evidence conflicts, **stop** and investigate manually. There is no skip/delete/force-publish endpoint.
5. Validate restored ordering and consumer idempotency with a disposable Testcontainers/MongoDB run. Actual multi-service Docker Compose and broker-outage testing is deferred to local validation.

## Validation and limits

CI includes MongoDB replica-set integration coverage for quarantine, ordered blocking, immutable event-ID redrive, exact idempotency, incorrect/unknown events, a disabled mode and the three-redrive budget. The Java/test suite must pass on the PR before merging. The runbook is a workflow description, **not** proof of a complete chaos-recovery drill.

This is still at-least-once delivery. Application-level quarantine does not add RabbitMQ dead-letter exchanges, confirm-mode publish acknowledgements, durable consumer outcome acknowledgements, or business-specific saga compensation. For production, add explicit transport confirmations, independent operational alerts, replay governance, retention/backup testing and cross-service fault injection before enabling corrective writes.
