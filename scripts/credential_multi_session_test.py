#!/usr/bin/env python3
"""Live auth.revoke test across two sessions using one saved credential.

The credential is intentionally revoked. On full success its local token file is
deleted after a fresh connection observes token_revoked. Optional stale
credentials are revoked first by explicit credential ID.
"""

import argparse
import json
import sys
import uuid

from pair_test import PROTOCOL, RpcClient, load_token_file


def credential_id(raw: str) -> str:
    try:
        parsed = uuid.UUID(raw)
    except ValueError as error:
        raise argparse.ArgumentTypeError("credential ID must be a UUID") from error
    canonical = str(parsed)
    if raw != canonical:
        raise argparse.ArgumentTypeError(f"credential ID must be canonical: {canonical}")
    return canonical


def hello(client: RpcClient, token: str, protocol: str, label: str) -> dict:
    response = client.call("hello", {"protocol": protocol, "auth": {"token": token}})
    if "error" in response:
        raise AssertionError(f"{label} hello -> {response['error']}")
    player = response["result"].get("player")
    if not player:
        raise AssertionError(f"{label} hello did not bind a player")
    print(f"[{label}.hello] <- player={player}")
    return response["result"]


def list_credentials(client: RpcClient) -> tuple[list[dict], dict]:
    response = client.call("auth.listCredentials", [])
    if "error" in response:
        raise AssertionError(f"auth.listCredentials -> {response['error']}")
    credentials = response["result"].get("credentials")
    if not isinstance(credentials, list):
        raise AssertionError(f"credentials must be a list: {response}")
    current = [item for item in credentials if item.get("current") is True]
    if len(current) != 1:
        raise AssertionError(f"expected exactly one current credential: {credentials}")
    return credentials, current[0]


def revoke(client: RpcClient, target_id: str, label: str) -> None:
    response = client.call("auth.revoke", [target_id])
    result = response.get("result", {})
    if "error" in response or result != {"credential_id": target_id, "revoked": True}:
        raise AssertionError(f"{label} auth.revoke -> {response}")
    print(f"[{label}.revoke] <- revoked=true id={target_id}")


def parse_args():
    parser = argparse.ArgumentParser(
        description="Verify auth.revoke closes every session using one credential"
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--protocol", default=PROTOCOL)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--close-timeout", type=float, default=5.0)
    parser.add_argument("--token-file", required=True)
    parser.add_argument(
        "--revoke-credential", action="append", default=[], type=credential_id,
        metavar="UUID", help="explicit non-current credential to revoke first; repeatable",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    clients: list[RpcClient] = []
    try:
        token, token_type, token_path = load_token_file(args.token_file)
        if token_type != "long_lived":
            raise AssertionError("multi-session revoke requires a long-lived credential")
        print(f"[token.file] <- loaded type={token_type} path={token_path}")

        first = RpcClient(args.host, args.port, args.timeout)
        second = RpcClient(args.host, args.port, args.timeout)
        clients.extend((first, second))
        first_hello = hello(first, token, args.protocol, "session-1")
        second_hello = hello(second, token, args.protocol, "session-2")
        if first_hello.get("player") != second_hello.get("player"):
            raise AssertionError("the two sessions resolved different players")

        credentials, current = list_credentials(first)
        current_id = current.get("credential_id")
        if not isinstance(current_id, str):
            raise AssertionError(f"current credential has no ID: {current}")
        print(f"[auth.listCredentials] <- active={len(credentials)} current={current_id}")

        for stale_id in args.revoke_credential:
            if stale_id == current_id:
                raise AssertionError("--revoke-credential must not target the current credential")
            revoke(first, stale_id, "stale")
            remaining, still_current = list_credentials(first)
            if any(item.get("credential_id") == stale_id for item in remaining):
                raise AssertionError(f"revoked credential still listed: {stale_id}")
            if still_current.get("credential_id") != current_id:
                raise AssertionError("revoking a stale credential changed current")
            print(f"[auth.listCredentials] <- active={len(remaining)} stale-removed=true")

        revoke(first, current_id, "current")
        first_closed = first.expect_closed(args.close_timeout)
        second_closed = second.expect_closed(args.close_timeout)
        if not first_closed or not second_closed:
            raise AssertionError(
                "revoke did not close every bound session: "
                f"session-1={first_closed} session-2={second_closed}"
            )
        print("[socket.close] <- session-1=true session-2=true")

        reconnect = RpcClient(args.host, args.port, args.timeout)
        clients.append(reconnect)
        response = reconnect.call(
            "hello", {"protocol": args.protocol, "auth": {"token": token}}
        )
        reason = response.get("error", {}).get("data", {}).get("reason")
        if reason != "token_revoked":
            raise AssertionError(f"reconnect expected token_revoked, got {response}")
        print("[reconnect.hello] <- token_revoked (expected)")

        token_path.unlink()
        print(f"[token.file] -> deleted path={token_path}")
        print()
        print("PASS: auth.revoke closed all credential sessions and rejected reconnect")
        return 0
    except AssertionError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    except (OSError, RuntimeError, UnicodeError, ValueError,
            json.JSONDecodeError, KeyError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    finally:
        for client in clients:
            try:
                client.close()
            except OSError:
                pass


if __name__ == "__main__":
    sys.exit(main())
