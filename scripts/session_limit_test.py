#!/usr/bin/env python3
"""McRemote same-UUID session limit smoke test.

1つの token で複数 hello connection を保持し、auth.max_sessions_per_uuid の上限で
次の hello が too_many_sessions になることを確認する。token を指定しない場合は
auth.pairBegin -> /mcremote pair -> auth.pairPoll で取得する。
"""
import argparse
import json
import socket
import sys
import time

PROTOCOL = "21.0.0"


class Conn:
    def __init__(self, host: str, port: int, timeout: float):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        self.reader = self.sock.makefile("rb")
        self.next_id = 0

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
            raise RuntimeError(f"{method} id mismatch: want {rid}, got {resp.get('id')}")
        return resp

    def close(self):
        self.sock.close()


def reason(resp: dict) -> str | None:
    return resp.get("error", {}).get("data", {}).get("reason")


def pair_for_token(args) -> str:
    conn = Conn(args.host, args.port, args.timeout)
    try:
        begin = conn.call("auth.pairBegin", {
            "token_type": "session",
            "client": {"name": "session_limit_test.py", "version": "0", "locale": "ja"},
        })
        if "error" in begin:
            raise RuntimeError(f"auth.pairBegin failed: {begin['error']}")
        result = begin["result"]
        code = result["pair_code"]
        print(f"[pairBegin] pairing_id={result['pairing_id']} expires_in={result['expires_in']}s")
        print(f"  run in Minecraft chat: /mcremote pair {code[:3]}-{code[3:]}")

        deadline = time.monotonic() + result["expires_in"]
        while time.monotonic() < deadline:
            poll = conn.call("auth.pairPoll", {"pairing_id": result["pairing_id"]})
            if "error" in poll:
                raise RuntimeError(f"auth.pairPoll failed: {poll['error']}")
            status = poll["result"].get("status")
            if status == "ok":
                token = poll["result"]["token"]
                print(f"[pairPoll] ok token={token[:12]}...")
                return token
            if status != "pending":
                raise RuntimeError(f"unexpected pairPoll status: {status!r}")
            print("[pairPoll] pending ...")
            time.sleep(args.poll_interval)
        raise RuntimeError("timed out waiting for /mcremote pair")
    finally:
        conn.close()


def main() -> int:
    ap = argparse.ArgumentParser(description="McRemote same-UUID session limit smoke test")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25575)
    ap.add_argument("--protocol", default=PROTOCOL)
    ap.add_argument("--timeout", type=float, default=10.0)
    ap.add_argument("--poll-interval", type=float, default=1.5)
    ap.add_argument("--limit", type=int, default=16)
    ap.add_argument("--token", default=None, help="existing mcrs_/mcrp_ token; skips pairing")
    args = ap.parse_args()

    held: list[Conn] = []
    try:
        token = args.token or pair_for_token(args)
        for i in range(args.limit):
            conn = Conn(args.host, args.port, args.timeout)
            resp = conn.call("hello", {"protocol": args.protocol, "auth": {"token": token}})
            if "error" in resp:
                print(f"FAIL: hello #{i + 1} expected success, got {resp['error']}")
                conn.close()
                return 1
            held.append(conn)
            print(f"[hello #{i + 1}] success player={resp['result'].get('player')}")

        overflow = Conn(args.host, args.port, args.timeout)
        resp = overflow.call("hello", {"protocol": args.protocol, "auth": {"token": token}})
        overflow.close()
        if reason(resp) != "too_many_sessions":
            print(f"FAIL: overflow expected too_many_sessions, got {resp}")
            return 1
        data = resp["error"].get("data", {})
        print(f"[hello #{args.limit + 1}] PASS reason=too_many_sessions current={data.get('current')} limit={data.get('limit')}")
        return 0
    except (OSError, RuntimeError, json.JSONDecodeError, KeyError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2
    finally:
        for conn in held:
            conn.close()


if __name__ == "__main__":
    sys.exit(main())
