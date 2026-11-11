#!/usr/bin/env python3
"""Offline contract tests; no Docker, network or customer tokens required."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


SOURCE = Path(__file__).with_name("verify-portfolio-demo.py")
SPEC = importlib.util.spec_from_file_location("portfolio_demo", SOURCE)
demo = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(demo)


class FakeClient:
    def __init__(self, product_id=321001):
        self.product_id = product_id
        self.calls = []
        self.reads = 0
        self.receipts = 0

    def request(self, method, path, payload=None, token="user", headers=None):
        self.calls.append((method, path, payload, token, headers))
        if path == "/actuator/health":
            return 200, {"status": "UP"}
        if token == "none":
            return 401, None
        if path == "/product-composite":
            return 202, None
        if path == f"/product-composite/{self.product_id}":
            self.reads += 1
            if self.reads == 1:
                return 404, None
            if self.reads == 2:
                return 200, {"productId": self.product_id, "reviews": [],
                             "recommendations": []}
            return 200, {"productId": self.product_id,
                         "reviews": [{"reviewId": 1}],
                         "recommendations": [{"recommendationId": 1}]}
        if path == f"/product-composite/cart/items/{self.product_id}":
            return 200, {"items": [{"productId": self.product_id, "quantity": 1}]}
        if path == "/product-composite/orders":
            return 200, {"orderId": "local-mock-order"}
        if path == "/product-composite/orders/local-mock-order":
            return 200, {"orderId": "local-mock-order"}
        if path == (f"/product-composite/fulfillment/aggregates/"
                    f"{self.product_id}/operations?limit=10"):
            return 200, [{"operationId": "00000000-0000-0000-0000-000000000001"}]
        if path == ("/product-composite/fulfillment/operations/"
                    "00000000-0000-0000-0000-000000000001/receipts"):
            self.receipts += 1
            if self.receipts == 1:
                return 200, {"status": "RECEIPTS_MISSING"}
            return 200, {"status": "ALL_RECEIPTS_VERIFIED",
                         "expectedEvents": 3, "verifiedEvents": 3}
        raise AssertionError(f"Unexpected {method} {path}")


class PortfolioDemoTests(unittest.TestCase):
    def test_local_only_and_no_url_credentials(self):
        for base in ("https://example.com", "http://example.com:8443",
                     "http://user:password@localhost:8443",
                     "http://localhost:8443/path", "file:///tmp"):
            with self.subTest(base=base):
                with self.assertRaises(demo.DemoError):
                    demo.DemoClient(base, "dummy")
        self.assertEqual("http://localhost:8443",
                         demo.DemoClient("http://localhost:8443/", "dummy").base_url)

    def test_requires_explicit_execute_flag(self):
        with self.assertRaises(SystemExit):
            demo.parse_args([])
        with self.assertRaises(SystemExit):
            demo.parse_args(["--execute", "--attempts", "0"])
        with self.assertRaises(SystemExit):
            demo.parse_args(["--execute", "--product-id", "2147483648"])

    def test_waits_for_all_three_destinations_not_just_http_200(self):
        client = FakeClient()
        sleeps = []
        result = demo.poll_aggregate(client, 321001, 4, 0.1, sleep=sleeps.append)
        self.assertTrue(demo.complete_aggregate(result, 321001))
        self.assertEqual(2, len(sleeps))
        self.assertEqual(3, client.reads)

    def test_rejects_partial_response_after_bounded_poll(self):
        client = FakeClient()
        with self.assertRaises(demo.DemoError):
            demo.poll_aggregate(client, 321001, 2, 1, sleep=lambda _: None)

    def test_checkout_reuses_same_idempotency_key(self):
        client = FakeClient()
        order_id = demo.check_checkout(client, 321001)
        self.assertEqual("local-mock-order", order_id)
        checkouts = [call for call in client.calls
                     if call[0] == "POST" and call[1] == "/product-composite/orders"]
        self.assertEqual(2, len(checkouts))
        self.assertEqual(checkouts[0][4]["Idempotency-Key"],
                         checkouts[1][4]["Idempotency-Key"])

    def test_operator_evidence_requires_matching_receipts(self):
        client = FakeClient()
        demo.check_operator(client, 321001, attempts=3, interval=0.1,
                            sleep=lambda _: None)
        self.assertEqual(2, client.receipts)
        self.assertTrue(all(x[3] == "operator" for x in client.calls
                            if "/fulfillment/" in x[1]))

    def test_verified_receipts_cannot_be_claimed_from_bad_counts(self):
        client = FakeClient()
        client.receipts = 1
        original = client.request
        def bad_count(method, path, payload=None, token="user", headers=None):
            if path.endswith("/receipts"):
                return 200, {"status": "ALL_RECEIPTS_VERIFIED",
                             "expectedEvents": 3, "verifiedEvents": 2}
            return original(method, path, payload, token, headers)
        client.request = bad_count
        with self.assertRaises(demo.DemoError):
            demo.check_operator(client, 321001, 1, 0.1, sleep=lambda _: None)

    def test_entrypoint_full_synthetic_flow_no_real_requests(self):
        fake = FakeClient(321001)
        with patch.object(demo, "DemoClient", return_value=fake), patch.dict(
                demo.os.environ,
                {"ACCESS_TOKEN": "test-only-user-token",
                 "OPERATOR_TOKEN": "test-only-operator-token"}):
            self.assertEqual(0, demo.main([
                "--execute", "--product-id", "321001", "--checkout", "--operator",
                "--attempts", "4", "--interval", "0.01"]))
        self.assertEqual(3, fake.reads)
        self.assertEqual(2, fake.receipts)

    def test_operator_token_is_mandatory_in_operator_mode(self):
        with patch.dict(demo.os.environ, {"ACCESS_TOKEN": "test"}, clear=True):
            with self.assertRaises(demo.DemoError):
                demo.main(["--execute", "--operator"])


if __name__ == "__main__":
    unittest.main()
