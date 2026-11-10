# Operator triage: review queue and append-only notes

This phase makes the opt-in saga supervisor's `NEEDS_REVIEW` outcomes actionable for human investigation **without executing any repair**. It is a bounded, metadata-only workflow, not distributed transaction compensation.

## Endpoints and scopes

```http
GET /product-composite/fulfillment/supervisions/review-queue?limit=20
Authorization: Bearer <fulfillment:read operator JWT>
```

Returns the oldest `NEEDS_REVIEW` supervision records first, limited to 1–50 per call (default 20). It is intentionally a bounded queue view, not a paginated full history. The queue may be empty when the supervisor is disabled or no incidents are flagged.

```http
GET /product-composite/fulfillment/operations/{operationId}/triage?limit=20
Authorization: Bearer <fulfillment:read operator JWT>
```

Returns newest notes first for the specified known supervised operation, limited to 1–50. No note contents or identity claims from other services are read.

```http
POST /product-composite/fulfillment/operations/{operationId}/triage
Authorization: Bearer <fulfillment:write operator JWT>
Idempotency-Key: case-20261110-investigation-01
Content-Type: application/json

{
  "category": "INVESTIGATING",
  "note": "Checking outbox state and transactional consumer receipt evidence."
}
```

Permitted categories: `INVESTIGATING`, `EVIDENCE_COLLECTED`, `ESCALATED`, `FOLLOW_UP_REQUIRED`. Notes must be 16–500 characters after trimming and contain no control characters. Do not include names, secrets, raw JWTs, customer data, payment details or untrusted markup. The endpoint accepts notes **only while** the operation remains `NEEDS_REVIEW`; otherwise it returns 409. Unknown operation IDs return 404 and malformed requests return 400.

## Durability and idempotency

Each note is an independent, immutable MongoDB document in `saga_triage_notes`. The document ID is a SHA-256 digest of operation ID and caller-selected `Idempotency-Key`. The first accepted submission stores the note; reusing the same key **and** actor/category/body returns the same document. Reusing the key with changed actor or content returns 409. Concurrent first writes rely on the unique MongoDB `_id` index; no previous note is overwritten.

The note stores a SHA-256 fingerprint of JWT issuer+subject, not the raw user ID, token or email. It is still potentially correlatable metadata and must be handled under appropriate data-retention and access policies. Do **not** use unhashed subject claims in audit screenshots. The reviewer must have a legitimate signed JWT; service-level scope enforcement lives in the composite security policy.

Investigation notes are append-only **within this API**. Database administrators with direct access could alter them; strict tamper-proof audit trails would require an external protected ledger, retention policies, and backups. This feature does not provide a lock or ownership assignment. It also does not mark cases complete or restart the supervisor.

## Incident workflow

1. Get the review queue; identify a `NEEDS_REVIEW` operation.
2. Inspect the original outbox operation, receipt audit, and aggregate reconciliation snapshots. Correlate event IDs and publisher/consumer logs, without exposing payloads.
3. Add an investigation note with an incident-specific idempotency key; use another unique key for a later update. Retry the same request safely after a network timeout.
4. Escalate manually if the journal is incomplete, evidence conflicts, or the check budget is exhausted. No automatic repair is triggered.
5. Any eventual controlled replay, resolution, or compensation must be designed and reviewed separately with tested fencing and explicit policies.

## Known limitations

The review queue is a live, bounded view and may change between calls. Notes do not provide an atomic snapshot of the case; a race with a supervisor or direct database writes can affect state between validation and insertion. As a result, note eligibility is checked at request time rather than being a transactional claim, and triage notes must never authorize business mutations. Deployed MongoDB should have the recommended `triage_by_operation_idx` index for bounded per-operation lookups; verify it exists rather than relying solely on mapping annotations.

## Reviewing a case again

After documenting a `NEEDS_REVIEW` incident, use [recovery operator controls](RECOVERY_CONTROLS.md) to request a bounded, idempotent **receipt recheck**. This does **not** replay events, compensate business state, or mark the case resolved. The recheck request has its own `fulfillment:write` scope and a separate audit record.
