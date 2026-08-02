#!/usr/bin/env python3
"""Non-interactive live checks for the long-lived credential pre-hello contract."""

import argparse
import json
import socket
import sys


def call_once(host: str, port: int, timeout: float, method: str, params) -> dict:
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        request = {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}
        sock.sendall((json.dumps(request, separators=(",", ":")) + "\n").encode())
        response = sock.makefile("rb").readline()
        if not response:
            raise RuntimeError(f"{method}: server closed without a response")
        return json.loads(response)


def reason(response: dict) -> str | None:
    return response.get("error", {}).get("data", {}).get("reason")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--timeout", type=float, default=10.0)
    args = parser.parse_args()

    cases = [
        ("legacy-player-type", {"token_type": "player", "client": {}}, "invalid_params"),
        ("unknown-type", {"token_type": "forever", "client": {}}, "invalid_params"),
        ("blank-device", {"token_type": "long_lived", "client": {}, "device": "  "},
         "invalid_params"),
    ]
    for label, params, expected in cases:
        response = call_once(args.host, args.port, args.timeout, "auth.pairBegin", params)
        actual = reason(response)
        if actual != expected:
            print(f"FAIL [{label}]: reason={actual!r}, want={expected!r}: {response}")
            return 1
        print(f"PASS [{label}] -> {actual}")

    valid = call_once(args.host, args.port, args.timeout, "auth.pairBegin", {
        "token_type": "long_lived",
        "client": {"name": "credential_contract_test.py", "version": "0", "locale": "ja"},
        "device": "contract-test",
    })
    result = valid.get("result", {})
    if not (isinstance(result.get("pairing_id"), str)
            and isinstance(result.get("pair_code"), str)
            and len(result["pair_code"]) == 6):
        print(f"FAIL [long-lived-begin]: {valid}")
        return 1
    print("PASS [long-lived-begin] -> pairing_id + six-digit pair_code")
    print("PASS: long-lived pre-hello credential contract")
    return 0


if __name__ == "__main__":
    sys.exit(main())
