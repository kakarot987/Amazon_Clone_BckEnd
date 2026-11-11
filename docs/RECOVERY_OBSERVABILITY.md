# Outbox and saga observability

The product-composite service publishes **read-only, low-cardinality** Micrometer gauges from its existing composite MongoDB state. These metrics help operators decide when to investigate; they do not repair messages, restart workers or change business data.

## Metrics emitted

All names below are Prometheus names. The Java gauge names use dots; Micrometer normalizes them to underscores.

| Metric | Meaning |
| --- | --- |
| `commerce_reliability_outbox_undelivered` | Count of outbox records in `PENDING`, `IN_FLIGHT` or `QUARANTINED` |
| `commerce_reliability_outbox_quarantined` | Original outbox records held in an ordering-preserving operator-review state |
| `commerce_reliability_outbox_oldest_age_seconds` | Age of the oldest undelivered event, or zero if there is no queue head |
| `commerce_reliability_saga_pending` | Supervision documents awaiting inspection |
| `commerce_reliability_saga_checking` | Supervision documents currently leased for inspection |
| `commerce_reliability_saga_waiting` | Supervision documents awaiting another check |
| `commerce_reliability_saga_receipts_verified` | Operations with historical matching inbox receipts; **not** globally completed sagas |
| `commerce_reliability_saga_needs_review` | Supervised operations flagged for human triage |
| `commerce_reliability_telemetry_last_success_epoch_seconds` | Unix epoch seconds of the most recent fully successful MongoDB telemetry read |

No labels include event IDs, product IDs, JWT claims, tenant/customer data, payload content, or lease tokens. A bounded background refresh runs on the composite's existing scheduler every 30 seconds (initial delay 15 seconds). Reads are independent: counts and oldest-event age are an **approximate operational snapshot**, not one MongoDB transaction or an exact cross-service state. MongoDB failures do not rewrite previous values to zero; monitor the last-success timestamp to identify stale readings. Before the first successful refresh, gauges initialize to zero, so **also monitor scrape/refresh health**.

Metrics are on the **existing** internal composite `/actuator/prometheus` target. The default Prometheus configuration scrapes the composite service through the Compose network; do not expose its actuator endpoints on the public internet.

## Alerts

`monitoring/rules.yml` includes threshold examples:

- `CommerceOutboxUndeliveredBacklog`: more than 100 undelivered events for 10 minutes.
- `CommerceOutboxOldestEventStuck`: an oldest event older than 15 minutes, for at least 5 minutes, while there is pending backlog.
- `CommerceSagaNeedsManualReview`: at least one operation needs review for 5 minutes.
- `CommerceRecoveryTelemetryStale`: telemetry hasn't refreshed for more than 3 minutes, sustained for 2 minutes.
- `CommerceCompositeMetricsMissing`: Prometheus cannot scrape the composite endpoint for 2 minutes.
- `CommercePublisherOutboxQuarantined`: One or more quarantined original events block ordered publication for 2 minutes.

These thresholds are **portfolio demonstration defaults**, not tuned SLOs. With no matching time series, Prometheus may not evaluate staleness comparisons; also use `up{job="commerce-composite"}` to detect exporter absence. The existing Grafana commerce dashboard adds dedicated charts for backlog, oldest age, supervision states and telemetry staleness. It does not configure Alertmanager delivery, an on-call service or production alert routing.

## Operational failure drill

1. Start a healthy local stack with a disposable aggregate and verify `/actuator/prometheus` on the private composite endpoint.
2. Observe the new series in Prometheus and Grafana. Poll the existing operator recovery overview to correlate any oldest undelivered event without revealing the payload.
3. On **disposable local infrastructure only**, stop RabbitMQ, enqueue a new aggregate intent and observe eventual backoff/age. A `202` means intent stored transactionally, **not** downstream completion.
4. Restart RabbitMQ, verify the logs and eventual consumer inbox evidence. Do not claim recovery if a poison event or misconfiguration prevents progress.
5. Restore the stack and ensure the alert conditions return below their thresholds. Never submit secrets or private customer information in sample data or screenshots.

## Validation, performance and caveats

The unit tests verify correct gauge values, empty-backlog semantics and preserving stale readings after MongoDB errors. The CI workflow checks dashboard JSON and alert names alongside the Java build/Testcontainers tests. MongoDB `count` queries run on a schedule: monitor query cost and add suitable indexes for larger deployments. Long-running outbox/saga collections may need index management, retention, and cardinality controls.

This is **monitoring**, not durable publisher confirmation, exactly-once delivery, automatic saga compensation, alert dispatch, or a tested production SLO.
