#!/usr/bin/env python3
"""Opt-in RabbitMQ outage/recovery drill for disposable local Docker Compose only."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ["docker", "compose", "-f", "docker-compose.yml"]
spec = importlib.util.spec_from_file_location(
    "portfolio_demo", Path(__file__).resolve().parent / "verify-portfolio-demo.py")
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)
DemoError = demo.DemoError


def docker(argv, timeout=50):
    """Do not log Docker output; it can contain local configuration secrets."""
    try:
        result = subprocess.run(argv, cwd=ROOT, capture_output=True, text=True,
                                timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise DemoError("Local Docker command unavailable or timed out") from exc
    if result.returncode:
        raise DemoError("Docker command failed; inspect disposable local stack")
    return result.stdout.strip()


def require_local_docker(run):
    override = os.environ.get("DOCKER_HOST")
    if override and not override.startswith(("unix://", "npipe://")):
        raise DemoError("Refusing to operate on nonlocal DOCKER_HOST")
    raw = run(["docker", "context", "inspect", "--format",
               "{{json .Endpoints.docker.Host}}"])
    try:
        endpoint = json.loads(raw)
    except (TypeError, ValueError) as exc:
        raise DemoError("Cannot inspect local Docker context") from exc
    if not isinstance(endpoint, str) or not endpoint.startswith(("unix://", "npipe://")):
        raise DemoError("Refusing to operate on nonlocal Docker context")


def broker_running(run):
    return bool(run(COMPOSE + ["ps", "--status", "running", "-q", "rabbitmq"]))


def restore_broker(run, sleep, attempts=40):
    run(COMPOSE + ["start", "rabbitmq"], timeout=120)
    for index in range(attempts):
        try:
            run(COMPOSE + ["exec", "-T", "rabbitmq",
                           "rabbitmq-diagnostics", "-q", "ping"])
            return
        except DemoError:
            if index + 1 < attempts:
                sleep(2)
    raise DemoError("RabbitMQ restarted but did not pass health checks")


def save_report(client, product_id, started, stopped, restored):
    """Create a private report only after all live verification has succeeded."""
    now = datetime.now(timezone.utc)
    stamp = lambda dt: dt.isoformat(timespec="seconds").replace("+00:00", "Z")
    report = {
        "schema_version": 1,
        "source": "disposable-local-rabbitmq-drill",
        "recorded_at_utc": stamp(now),
        "started_at_utc": stamp(started),
        "rabbitmq_stopped_at_utc": stamp(stopped),
        "rabbitmq_restored_at_utc": stamp(restored),
        "gateway_url": client.base_url,
        "synthetic_product_id": product_id,
        "checks": {
            "local_docker_socket": "passed",
            "outage_http_202": "passed",
            "incomplete_aggregate_during_outage": "passed",
            "broker_restored_healthy": "passed",
            "three_service_convergence": "passed",
            "signed_consumer_receipts": "passed"
        },
        "not_verified": ["production_high_availability", "exactly_once_delivery",
                         "business_compensation", "real_payment_processing"]
    }
    try:
        demo.EVIDENCE_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
        destination = demo.EVIDENCE_DIR / (
            "broker-recovery-" + now.strftime("%Y%m%dT%H%M%SZ")
            + "-" + secrets.token_hex(4) + ".json")
        fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(report, output, indent=2, sort_keys=True)
            output.write("\n")
    except OSError as exc:
        raise DemoError("Unable to write private broker recovery report") from exc
    print("PASS saved private recovery report: " + str(destination), flush=True)
    return destination


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--disposable-stack", action="store_true")
    parser.add_argument("--save-evidence", action="store_true")
    parser.add_argument("--base-url", default=os.getenv("BASE_URL", demo.DEFAULT_BASE))
    parser.add_argument("--product-id", type=int)
    parser.add_argument("--attempts", type=int, default=60)
    parser.add_argument("--interval", type=float, default=3)
    args = parser.parse_args(argv)
    if not args.execute or not args.disposable_stack:
        parser.error("Both --execute and --disposable-stack are required")
    if not (1 <= args.attempts <= 200 and 0 < args.interval <= 60):
        parser.error("Invalid polling attempts or interval")
    if args.product_id is not None and not 1 <= args.product_id <= 2_147_483_647:
        parser.error("Product ID must be a positive 32-bit integer")
    return args


def run_drill(args, run=docker, sleep=time.sleep, client=None):
    if not args.execute or not args.disposable_stack:
        raise DemoError("Explicit consent required before broker stop")
    user_token, operator_token = os.getenv("ACCESS_TOKEN"), os.getenv("OPERATOR_TOKEN")
    if not user_token or not operator_token:
        raise DemoError("Set ACCESS_TOKEN and OPERATOR_TOKEN in local environment")
    client = client or demo.DemoClient(args.base_url, user_token, operator_token)
    require_local_docker(run)
    if not broker_running(run):
        raise DemoError("RabbitMQ is not running in the local Compose project")

    product_id = args.product_id or 100_000 + secrets.randbelow(1_900_000_000)
    demo.check_gateway(client, product_id)
    started = datetime.now(timezone.utc)
    print("Disposable broker drill productId=" + str(product_id), flush=True)
    # The finally block also runs when the stop command partially succeeds then fails.
    try:
        run(COMPOSE + ["stop", "rabbitmq"], timeout=120)
        if broker_running(run):
            raise DemoError("RabbitMQ still running after stop")
        stopped = datetime.now(timezone.utc)
        status, _ = client.request("POST", "/product-composite",
                                   demo.sample_product(product_id))
        demo.require_status(status, 202, "Create while broker stopped")
        read_status, partial = client.request(
            "GET", "/product-composite/" + str(product_id))
        if read_status == 200 and demo.complete_aggregate(partial, product_id):
            raise DemoError("Aggregate fully visible during outage; test inconclusive")
        if read_status not in (200, 404, 502, 503, 504):
            raise DemoError("Unexpected outage read HTTP " + str(read_status))
        print("PASS HTTP 202 during outage; aggregate not complete", flush=True)
    finally:
        try:
            restore_broker(run, sleep)
        except DemoError as exc:
            raise DemoError(
                "CRITICAL: automatic restart failed. Run "
                "'docker compose start rabbitmq' locally and check broker health."
            ) from exc

    restored = datetime.now(timezone.utc)
    print("PASS broker restarted and answered health probe", flush=True)
    demo.poll_aggregate(client, product_id, args.attempts, args.interval, sleep=sleep)
    demo.check_operator(client, product_id, args.attempts, args.interval, sleep=sleep)
    print("PASS aggregate fan-out and signed receipts after recovery", flush=True)
    if args.save_evidence:
        save_report(client, product_id, started, stopped, restored)
    return 0


def interrupted(_signal, _frame):
    raise DemoError("Interrupted; attempting RabbitMQ restoration")


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, interrupted)
    try:
        sys.exit(run_drill(parse_args()))
    except DemoError as exc:
        print("FAIL " + str(exc), file=sys.stderr)
        sys.exit(1)
