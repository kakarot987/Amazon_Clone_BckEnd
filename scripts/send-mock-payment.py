#!/usr/bin/env python3
"""Send a signed *simulated* payment result. No payment card or provider is used.

Usage:
  MOCK_PAYMENT_WEBHOOK_SECRET=... python3 scripts/send-mock-payment.py \
    --payment-id <64-character-order-id> --outcome SUCCEEDED

The same --event-id can be sent again to test idempotent webhook retries.
"""
import argparse
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--payment-id", required=True)
    parser.add_argument("--outcome", required=True, choices=["SUCCEEDED", "DECLINED"])
    parser.add_argument("--event-id", default=None)
    parser.add_argument("--url", default="http://localhost:8443/product-composite/payments/mock-webhook")
    args = parser.parse_args()

    secret = os.environ.get("MOCK_PAYMENT_WEBHOOK_SECRET", "")
    if len(secret) < 32 or secret.startswith("replace-with"):
        parser.error("Set MOCK_PAYMENT_WEBHOOK_SECRET to a fresh random value of >= 32 characters")
    if len(args.payment_id) != 64 or not all(c in "0123456789abcdef" for c in args.payment_id):
        parser.error("--payment-id must be a 64-character lowercase hex order ID")

    event = {
        "eventId": args.event_id or str(uuid.uuid4()),
        "paymentId": args.payment_id,
        "outcome": args.outcome,
    }
    if len(event["eventId"]) < 8 or len(event["eventId"]) > 100:
        parser.error("--event-id must be 8-100 characters")

    payload = json.dumps(event, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    timestamp = str(int(time.time()))
    signed = timestamp.encode("ascii") + b"." + payload
    signature = hmac.new(secret.encode("utf-8"), signed, hashlib.sha256).hexdigest()
    request = urllib.request.Request(
        args.url,
        data=payload,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Mock-Timestamp": timestamp,
            "X-Mock-Signature": signature,
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            print(response.status, response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        print(error.code, error.read().decode("utf-8"), file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
