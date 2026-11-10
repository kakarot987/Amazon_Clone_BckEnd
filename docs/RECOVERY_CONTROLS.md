# Recovery operations: bounded rechecks and read-only observability

This milestone groups the human-in-the-loop **recheck** controls and a bounded dashboard into one PR. It does **not** support business-event replay, compensation, refunds, inventory adjustment or force-completion.

## Operational overview

```http
GET /product-composite/fulfillment/recovery/overview
Authorization: Bearer <fulfillment:read operator JWT>
```

Returns current counts of `PENDING`, `CHECKING`, `WAITING`, `RECEIPTS_VERIFIED` and `NEEDS_REVIEW` supervision documents, plus the **oldest undelivered outbox event** if any. The reported head includes original event ID, operation ID (null for legacy entries), binding, product ID, attempts, and retry/lease timestamps, **not payload JSON, lease tokens, or any customer identity**.

The outbox head `readiness` is `READY`, `RETRY_BACKOFF`, `LEASE_ACTIVE` or `LEASE_EXPIRED`, derived from local state and clock. An empty outbox head appears as null. Counts and head are separate MongoDB reads and **not one atomic snapshot**. This API never claims a broker-confirmed delivery, consumer commit, or globally consistent saga status. A single poisoned queue head can block subsequent publications.

## Controlled manual evidence recheck

```http
GET /product-composite/fulfillment/operations/{operationId}/rechecks?limit=10
Authorization: Bearer <fulfillment:read operator JWT>
```

Returns newest immutable recheck requests for an existing supervised operation; limit is 1–20.

```http
POST /product-composite/fulfillment/operations/{operationId}/rechecks
Authorization: Bearer <fulfillment:write operator JWT>
Idempotency-Key: case-12345678-recheck-01
Content-Type: application/json

{"reason":"CONSUMER_RESTORED"}
```

Permitted reasons: `EVIDENCE_UPDATED`, `CONSUMER_RESTORED`, `BROKER_RECOVERED` and `OPERATOR_REVIEW`. The operation ID must be a canonical UUID-style shape, the key must be 8–100 ASCII letters/digits/periods/underscores/dashes, and the caller must have a verified JWT issuer+subject. No free-text secrets, passwords, customer data or payment details are accepted as the reason.

**Safety and atomicity:**

- New requests are accepted **only** for `NEEDS_REVIEW` operations, with a maximum of **three manual rechecks per operation**. Exhausted cases return 409.
- Each request has a SHA-256-based immutable ID derived from operation UUID and idempotency key. Issuer+subject are stored **only as a digest**, not as raw identity claims.
- Exact request retries with the same actor and reason return the original record, **even if the first request has already reopened the case**. A conflicting actor or reason under the same key returns 409.
- In one composite MongoDB replica-set transaction, the request is inserted and the supervision changes from `NEEDS_REVIEW` to `PENDING` using a compare-and-set on state and generation. Any concurrent state change rolls back the request. The recheck resets check count to 0, clears old review reason and abandoned leases, and increments generation to fence stale workers.
- The request changes **only** composite supervision metadata. It never changes the operation or outbox event, broker message, consumer inbox, product, order, inventory, payment, refund or shipping record.
- If the supervisor is disabled (default `APP_SAGA_ENABLED=false`), a successful manual recheck leaves the record `PENDING`, with **no automatic polling**. If enabled, the worker may perform another bounded sequence of signed, **read-only** receipt checks. Each sequence may still end at `NEEDS_REVIEW`.

**Review before requesting:** confirm broker and consumer health, inspect original event IDs, read consumer receipt evidence, and record the operator's investigation notes. A missing receipt or delayed consumer is not permission to replay the original event. No event replay or force-approve endpoint exists.

## Validation and remaining work

Test the MongoDB transaction rollback, idempotency on repeated keys, concurrent operator requests, three-request lifetime budget, generation fencing, authorization scopes, and outbox-head reporting on a disposable environment. The Java/Testcontainers suite covers key cases but does not replace deployment failure testing. Verify physical indexes (`saga_supervision_due_idx` on supervision and `rechecks_by_operation_idx` on request history). Mapping annotations do not ensure indexes have been deployed.

True automatic recovery would require a separate design and reviewed PR with deterministic business compensations, strict event ordering, idempotency and signed outcome acknowledgements, safe quarantine/replay governance, and full cross-service fault-injection tests. Do not treat this metadata workflow as a complete business saga.
