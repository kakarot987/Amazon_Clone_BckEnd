# Disposable RabbitMQ outage/recovery drill

This opt-in tool tests a **disposable local** Docker Compose stack. It does not run against production, shared systems, or remote Docker daemons. Committing the script or running its offline unit tests does not stop any broker.

## Before executing

Use an **exclusive named project** such as `portfolio-recovery-demo`. Start that dedicated project with both Compose files:

```bash
docker compose -f docker-compose.yml -f docker-compose.recovery.yml --project-name portfolio-recovery-demo up --build -d
```

The recovery override gives the backend network a project-specific name rather than using the base file's fixed `my-network`. The script checks that the RabbitMQ container's Compose project label matches before any stop. Because Compose publishes fixed localhost ports, stop any conflicting *disposable* stack first; do not stop a shared/production service to make room.

Then confirm that RabbitMQ, gateway, and consumers are healthy. Keep another terminal open with the recovery command `docker compose -f docker-compose.yml -f docker-compose.recovery.yml --project-name portfolio-recovery-demo start rabbitmq`. In your local shell, set `ACCESS_TOKEN` for a signed-in demo user with `product:read product:write` and `OPERATOR_TOKEN` for a separate operator with `fulfillment:read`. Never place tokens in source, commands, recordings, or public logs.

## Run from repository root

```bash
python3 scripts/verify-broker-recovery.py --execute --disposable-stack --project-name portfolio-recovery-demo --save-evidence
```

The two consent flags are mandatory. The script checks that the Docker context uses a local socket or named pipe, confirms that RabbitMQ is initially running, and verifies gateway health and anonymous access denial. It then stops the local Compose `rabbitmq` service and submits a fresh synthetic product aggregate. The script expects HTTP 202 (outbox intent) while the broker is stopped and requires that product/review/recommendation readback has not completely converged during the outage.

A `finally` block attempts to restart RabbitMQ even when the write/check fails. After the broker passes `rabbitmq-diagnostics ping`, the script polls for all three catalog components and for signed consumer inbox receipts. A hard process termination, loss of host power, or Docker daemon failure may prevent automatic restoration. If the script reports CRITICAL, use `docker compose start rabbitmq` and inspect health manually.

Only a completely successful live run with `--save-evidence` creates a unique ignored `demo-evidence/broker-recovery-*.json` file with owner-only (0600) permissions. It records timestamps, local gateway, synthetic ID, bounded check statuses and explicit limitations, not bearer tokens, raw HTTP responses or customer information. Review evidence before sharing; it is not independently attested.

## Validation and limitations

Offline tests in GitHub Actions use fake Docker calls and fake HTTP clients; **they do not demonstrate actual broker recovery**. A passing local drill validates only this synthetic outage and restart. It is not proof of exactly-once global delivery, business compensation, production high availability or real-money payment processing.

When finished, use `docker compose -f docker-compose.yml -f docker-compose.recovery.yml --project-name portfolio-recovery-demo down` to stop only the disposable project. Add `-v` only if you intentionally want to delete its disposable data volumes.
