#!/usr/bin/env python3
"""Simulate return inspection and mock refund. This never moves real money."""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8443")
    parser.add_argument("--order-id", required=True)
    parser.add_argument("--action", required=True,
                        choices=["approve", "reject", "receive", "mock-refund"])
    parser.add_argument("--event-id", default=None)
    args = parser.parse_args()

    token = os.environ.get("RETURNS_ACCESS_TOKEN", "")
    if not token:
        parser.error("RETURNS_ACCESS_TOKEN is required (returns:manage scope)")
    if len(args.order_id) != 64 or any(c not in "0123456789abcdef" for c in args.order_id):
        parser.error("--order-id must be a 64-character lowercase hex ID")
    event_id = args.event_id or str(uuid.uuid4())
    if not 8 <= len(event_id) <= 100 or any(
            c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-"
            for c in event_id):
        parser.error("--event-id must be 8-100 safe characters")

    url = (args.base_url.rstrip("/") + "/product-composite/returns/"
           + args.order_id + "/" + args.action)
    body = json.dumps({"eventId": event_id}, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(url, data=body, method="POST", headers={
        "Authorization": "Bearer " + token, "Content-Type": "application/json",
    })
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            print(response.status, response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        print(exc.code, exc.read().decode("utf-8"), file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
