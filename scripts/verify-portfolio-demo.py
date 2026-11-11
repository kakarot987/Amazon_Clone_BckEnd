#!/usr/bin/env python3
"""Safe, bounded end-to-end checks for a disposable local portfolio stack.

Requires an explicit --execute flag for state-changing catalog/checkout operations.
Uses only Python standard library. No webhook, broker, or service mutations beyond
a fresh synthetic catalog aggregate and an optional one-item mock reservation.
"""
import argparse
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_BASE = "http://localhost:8443"


class DemoError(RuntimeError):
    pass


class DemoClient:
    def __init__(self, base_url, token, operator_token=None, timeout=12):
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme != "http" or parsed.hostname not in ("localhost", "127.0.0.1"):
            raise DemoError("Only local HTTP gateway URLs are permitted")
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise DemoError("BASE_URL must not contain user info, query, or fragment")
        if parsed.path not in ("", "/"):
            raise DemoError("BASE_URL must point to the gateway root")
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.operator_token = operator_token
        self.timeout = timeout

    def request(self, method, path, payload=None, token="user", headers=None):
        if not path.startswith("/") or path.startswith("//"):
            raise DemoError("Relative API path required")
        selected = self.token if token == "user" else (
            self.operator_token if token == "operator" else None)
        metadata = {"Accept": "application/json"}
        if selected:
            metadata["Authorization"] = "Bearer " + selected
        if headers:
            metadata.update(headers)
        data = None
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            metadata["Content-Type"] = "application/json"
        req = urllib.request.Request(self.base_url + path, data=data, headers=metadata,
                                     method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as response:
                raw = response.read(500_000)
                return response.status, self._json(raw)
        except urllib.error.HTTPError as error:
            # Never print response bodies: they might contain credentials or PII.
            error.read(1024)
            return error.code, None
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise DemoError("Gateway unavailable; inspect local container logs") from error

    @staticmethod
    def _json(raw):
        if not raw:
            return None
        try:
            return json.loads(raw)
        except (ValueError, UnicodeDecodeError) as error:
            raise DemoError("Gateway returned invalid JSON") from error


def require_status(status, expected, label):
    if status != expected:
        raise DemoError(f"{label}: expected HTTP {expected}, got {status}")


def check_gateway(client, product_id):
    status, result = client.request("GET", "/actuator/health", token="none")
    require_status(status, 200, "Gateway health")
    if not isinstance(result, dict) or result.get("status") != "UP":
        raise DemoError("Gateway health did not report UP")
    status, _ = client.request("GET", f"/product-composite/{product_id}", token="none")
    if status not in (401, 403):
        raise DemoError(f"Anonymous product read was not denied (HTTP {status})")
    print("PASS gateway health and unauthenticated access denied", flush=True)


def sample_product(product_id):
    return {
        "productId": product_id, "name": "Portfolio Verified Demo Product",
        "weight": 750, "sku": f"PORTFOLIO-VERIFIED-{product_id}",
        "description": "Synthetic local test data, not a real offer",
        "category": "electronics", "price": 1499, "currency": "INR",
        "stockQuantity": 12, "imageUrls": [],
        "recommendations": [{
            "recommendationId": 1, "author": "Demo Author", "rate": 5,
            "content": "Synthetic recommendation"
        }],
        "reviews": [{
            "reviewId": 1, "author": "Demo Reviewer", "subject": "Synthetic",
            "content": "Synthetic review for local verification"
        }]
    }


def complete_aggregate(body, product_id):
    if not isinstance(body, dict) or body.get("productId") != product_id:
        return False
    recommendations = body.get("recommendations")
    reviews = body.get("reviews")
    if not isinstance(recommendations, list) or not isinstance(reviews, list):
        return False
    return (any(isinstance(item, dict) and item.get("recommendationId") == 1
                for item in recommendations)
            and any(isinstance(item, dict) and item.get("reviewId") == 1
                    for item in reviews))


def poll_aggregate(client, product_id, attempts, interval, sleep=time.sleep):
    for number in range(1, attempts + 1):
        status, body = client.request("GET", f"/product-composite/{product_id}")
        if status == 200 and complete_aggregate(body, product_id):
            print(f"PASS product, recommendation and review visible (poll {number})",
                  flush=True)
            return body
        if status in (401, 403):
            raise DemoError("JWT rejected/expired while verifying aggregate")
        if status not in (200, 404, 503, 502, 504):
            raise DemoError(f"Unexpected composite GET response: HTTP {status}")
        if number < attempts:
            sleep(interval)
    raise DemoError("Product/recommendation/review did not converge within poll budget")


def check_checkout(client, product_id):
    status, body = client.request("PUT", f"/product-composite/cart/items/{product_id}",
                                  {"quantity": 1})
    require_status(status, 200, "Set cart item")
    if not isinstance(body, dict) or not any(
        isinstance(x, dict) and x.get("productId") == product_id
        and x.get("quantity") == 1 for x in body.get("items", [])):
        raise DemoError("Cart does not contain requested synthetic item")
    key = "portfolio-" + secrets.token_hex(16)
    headers = {"Idempotency-Key": key}
    status, first = client.request("POST", "/product-composite/orders", headers=headers)
    require_status(status, 200, "Checkout")
    if not isinstance(first, dict) or not first.get("orderId"):
        raise DemoError("Checkout did not return an orderId")
    # Same key should not reserve inventory twice.
    status, repeated = client.request("POST", "/product-composite/orders", headers=headers)
    require_status(status, 200, "Idempotent checkout")
    if not isinstance(repeated, dict) or repeated.get("orderId") != first["orderId"]:
        raise DemoError("Checkout repeated with the same key produced a different order")
    status, retrieved = client.request("GET",
                                      "/product-composite/orders/" +
                                      urllib.parse.quote(first["orderId"], safe=""))
    require_status(status, 200, "Read reserved order")
    if not isinstance(retrieved, dict) or retrieved.get("orderId") != first["orderId"]:
        raise DemoError("Order read did not match checkout")
    print("PASS synthetic cart, checkout and same-key order idempotency", flush=True)
    return first["orderId"]


def check_operator(client, product_id, attempts, interval, sleep=time.sleep):
    status, operations = client.request(
        "GET", f"/product-composite/fulfillment/aggregates/{product_id}/operations?limit=10",
        token="operator")
    require_status(status, 200, "Operator operation listing")
    if not isinstance(operations, list) or not operations:
        raise DemoError("No tracked operation for synthetic product")
    operation_id = operations[0].get("operationId")
    if not operation_id:
        raise DemoError("Tracked operation missing operationId")
    for poll in range(1, attempts + 1):
        status, audit = client.request(
            "GET", "/product-composite/fulfillment/operations/" +
            urllib.parse.quote(operation_id, safe="") + "/receipts", token="operator")
        require_status(status, 200, "Operator signed receipt audit")
        if not isinstance(audit, dict):
            raise DemoError("Invalid signed receipt response")
        state = audit.get("status")
        if state == "ALL_RECEIPTS_VERIFIED":
            if audit.get("expectedEvents", 0) != 3 or audit.get("verifiedEvents") != 3:
                raise DemoError("Receipt count did not match the synthetic three-event batch")
            print(f"PASS three transactionally committed consumer receipts (poll {poll})",
                  flush=True)
            return
        if state in ("CONFLICT", "INCOMPLETE_JOURNAL"):
            raise DemoError(f"Operator receipt audit reported {state}")
        if state not in ("PUBLISH_PENDING", "RECEIPTS_MISSING", "INCONCLUSIVE"):
            raise DemoError(f"Unexpected receipt status {state}")
        if poll < attempts:
            sleep(interval)
    raise DemoError("Consumer receipts remained unverified within poll budget")


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true",
                        help="Allow synthetic catalog writes on localhost only")
    parser.add_argument("--checkout", action="store_true",
                        help="Also reserve one synthetic order (mock, no payment)")
    parser.add_argument("--operator", action="store_true",
                        help="Check signed inbox evidence with OPERATOR_TOKEN")
    parser.add_argument("--attempts", type=int, default=40)
    parser.add_argument("--interval", type=float, default=3)
    parser.add_argument("--base-url", default=os.getenv("BASE_URL", DEFAULT_BASE))
    parser.add_argument("--product-id", type=int,
                        help="Unique disposable positive Java int (default random)")
    args = parser.parse_args(argv)
    if args.attempts < 1 or args.attempts > 200 or args.interval <= 0 or args.interval > 60:
        parser.error("--attempts must be 1-200; --interval must be >0 and <=60")
    if not args.execute:
        parser.error("--execute is required before changing local catalog or cart")
    if args.product_id is not None and not (1 <= args.product_id <= 2_147_483_647):
        parser.error("--product-id must be a positive 32-bit signed integer")
    return args


def main(argv=None):
    args = parse_args(argv)
    token = os.getenv("ACCESS_TOKEN")
    operator_token = os.getenv("OPERATOR_TOKEN")
    if not token or (args.operator and not operator_token):
        raise DemoError("Set ACCESS_TOKEN (and OPERATOR_TOKEN with --operator) locally")
    product_id = args.product_id or (100_000 + secrets.randbelow(1_900_000_000))
    client = DemoClient(args.base_url, token, operator_token)
    print(f"Local synthetic scenario: productId={product_id} "
          f"checkout={args.checkout} operator={args.operator}", flush=True)
    check_gateway(client, product_id)
    status, _ = client.request("POST", "/product-composite", sample_product(product_id))
    require_status(status, 202, "Durable composite creation")
    print("PASS HTTP 202 persisted outbox intent (not downstream completion)",
          flush=True)
    poll_aggregate(client, product_id, args.attempts, args.interval)
    if args.checkout:
        check_checkout(client, product_id)
    if args.operator:
        check_operator(client, product_id, args.attempts, args.interval)
    print("PASS requested local demonstration checks completed", flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except DemoError as error:
        print(f"FAIL {error}", file=sys.stderr)
        sys.exit(1)
