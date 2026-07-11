#!/usr/bin/env python3
"""McRemote player.* smoke test (Python standard library only).

pair -> token -> hello の後、player.getPos / player.setPos を確認する。
LuckPerms あり・権限なし環境では --expect permission-denied を指定し、hello の
permission_denied 到達を b2 gate 証跡として扱う。
"""
import argparse
import json
import socket
import sys
import time

PROTOCOL = "21.0.0"


class Rpc:
    def __init__(self, host: str, port: int, timeout: float):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        self.reader = self.sock.makefile("rb")
        self.next_id = 0

    def close(self) -> None:
        self.sock.close()

    def call(self, method: str, params):
        self.next_id += 1
        rid = self.next_id
        msg = {"jsonrpc": "2.0", "id": rid, "method": method, "params": params}
        self.sock.sendall((json.dumps(msg, separators=(",", ":")) + "\n").encode("utf-8"))
        raw = self.reader.readline()
        if not raw:
            raise RuntimeError("connection closed by server")
        resp = json.loads(raw.decode("utf-8").rstrip("\r\n"))
        if resp.get("id") != rid:
            raise RuntimeError(f"{method} -> id mismatch: want {rid}, got {resp.get('id')}")
        return resp


def error_reason(resp: dict) -> str | None:
    return resp.get("error", {}).get("data", {}).get("reason")


def pair_and_hello(args) -> tuple[Rpc, dict]:
    rpc = Rpc(args.host, args.port, args.timeout)
    begin = rpc.call("auth.pairBegin", {
        "token_type": "session",
        "client": {"name": "player_test.py", "version": "0", "locale": "ja"},
    })
    if "error" in begin:
        raise RuntimeError(f"auth.pairBegin failed: {begin['error']}")
    result = begin["result"]
    pairing_id = result["pairing_id"]
    pair_code = result["pair_code"]
    grouped = f"{pair_code[:3]}-{pair_code[3:]}"
    print(f"[pairBegin] pairing_id={pairing_id} expires_in={result['expires_in']}s")
    print(f"  run in Minecraft chat: /mcremote pair {grouped}")

    deadline = time.monotonic() + result["expires_in"]
    token = None
    while time.monotonic() < deadline:
        poll = rpc.call("auth.pairPoll", {"pairing_id": pairing_id})
        if "error" in poll:
            raise RuntimeError(f"auth.pairPoll failed: {poll['error']}")
        status = poll["result"].get("status")
        if status == "ok":
            token = poll["result"]["token"]
            print(f"[pairPoll] ok token={token[:12]}...")
            break
        if status != "pending":
            raise RuntimeError(f"unexpected pairPoll status: {status!r}")
        print("[pairPoll] pending ...")
        time.sleep(args.poll_interval)
    if token is None:
        raise RuntimeError("timed out waiting for /mcremote pair")

    hello = rpc.call("hello", {"protocol": args.protocol, "auth": {"token": token}})
    return rpc, hello


def main() -> int:
    ap = argparse.ArgumentParser(description="McRemote player.* smoke test")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25575)
    ap.add_argument("--protocol", default=PROTOCOL)
    ap.add_argument("--timeout", type=float, default=10.0)
    ap.add_argument("--poll-interval", type=float, default=1.5)
    ap.add_argument("--expect", choices=["success", "permission-denied"], default="success")
    args = ap.parse_args()

    rpc = None
    try:
        rpc, hello = pair_and_hello(args)
        reason = error_reason(hello)
        if args.expect == "permission-denied":
            if reason == "permission_denied":
                print("[hello] PASS permission_denied")
                return 0
            print(f"FAIL: expected permission_denied, got {hello}")
            return 1

        if "error" in hello:
            print(f"FAIL: hello -> error {hello['error']}")
            return 1
        info = hello["result"]
        print(f"[hello] protocol={info.get('protocol')} player={info.get('player')} permissions={info.get('permissions')}")
        if not info.get("player"):
            print("FAIL: hello result has no player; token did not bind a UUID")
            return 1

        get_pos = rpc.call("player.getPos", [])
        if "error" in get_pos:
            print(f"FAIL: player.getPos -> error {get_pos['error']}")
            return 1
        world = get_pos["result"]["world"]
        pos = get_pos["result"]["pos"]
        print(f"[player.getPos] world={world} pos={pos}")

        set_pos = rpc.call("player.setPos", [world, pos[0], pos[1], pos[2]])
        if "error" in set_pos:
            print(f"FAIL: player.setPos -> error {set_pos['error']}")
            return 1
        print(f"[player.setPos] result={set_pos['result']}")
        print("PASS: pair -> hello -> player.getPos -> player.setPos")
        return 0
    except (OSError, RuntimeError, json.JSONDecodeError, KeyError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2
    finally:
        if rpc is not None:
            rpc.close()


if __name__ == "__main__":
    sys.exit(main())
