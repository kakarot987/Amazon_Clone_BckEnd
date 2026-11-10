#!/usr/bin/env python3
"""Update a simulated shipment. Does NOT book a real carrier or print addresses.

Examples:
  export FULFILLMENT_ACCESS_TOKEN='<token with fulfillment:write scope>'
  python3 scripts/simulate-shipment.py create --order-id <64-hex> --zone NORTH_01
  python3 scripts/simulate-shipment.py advance --order-id <64-hex> --status PACKED
  python3 scripts/simulate-shipment.py advance --order-id <64-hex> --status SHIPPED
  python3 scripts/simulate-shipment.py advance --order-id <64-hex> --status IN_TRANSIT
  python3 scripts/simulate-shipment.py advance --order-id <64-hex> --status DELIVERED

Use --event-id with a fixed value to retry the same milestone idempotently.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description="Mock shipment simulator only")
    parser.add_argument("--base-url", default="http://localhost:8443")
    sub = parser.add_subparsers(dest="command", required=True)
    create = sub.add_parser("create")
    create.add_argument("--order-id", required=True)
    create.add_argument("--zone", required=True)
    advance = sub.add_parser("advance")
    advance.add_argument("--order-id", required=True)
    advance.add_argument("--status", required=True,
                         choices=["PACKED", "SHIPPED", "IN_TRANSIT", "DELIVERED"])
    advance.add_argument("--event-id", default=None)
    args = parser.parse_args()

    token = os.environ.get("FULFILLMENT_ACCESS_TOKEN", "")
    if not token:
        parser.error("FULFILLMENT_ACCESS_TOKEN must contain a scoped operator access token")
    if len(args.order_id) != 64 or any(c not in "0123456789abcdef" for c in args.order_id):
        parser.error("--order-id must be 64 lowercase hexadecimal characters")

    if args.command == "create":
        url = args.base_url.rstrip("/") + "/product-composite/fulfillment/shipments"
        payload = {"orderId": args.order_id, "destinationZone": args.zone}
    else:
        url = (args.base_url.rstrip("/") + "/product-composite/fulfillment/shipments/"
               + args.order_id + "/events")
        payload = {
            "eventId": args.event_id or str(uuid.uuid4()),
            "status": args.status,
        }

    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(url, data=body, method="POST", headers={
        "Authorization": "Bearer " + token,
        "Content-Type": "application/json",
    })
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            print(response.status, response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        print(error.code, error.read().decode("utf-8"), file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
