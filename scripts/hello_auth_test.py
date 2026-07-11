#!/usr/bin/env python3
"""McRemote hello auth-enforcement smoke test (Python standard library only).

enforcement トグルの否定パスを player 不要・非対話で検証する（wire §6.1/§6.3・
versioning §10.11.1 item5）。pairing/player を要さないのは、token 欠落・無効の判定が
hello 単体で閉じるため（正の経路＝有効 token の束縛は pair_test.py が実プレイヤーで検証）。

  hello(protocol のみ・token 無し)      -> ON: error auth_required / OFF: success
  hello(auth:{token:"mcrs_bogus…"})    -> ON: error token_invalid  / OFF: success

使い方（サーバ側 config.yml の auth.enforcement に合わせて --expect を渡す）:
  python3 scripts/hello_auth_test.py --expect on     # enforcement: true のサーバ
  python3 scripts/hello_auth_test.py --expect off    # enforcement: false（既定）
"""
import argparse
import json
import socket
import sys

PROTOCOL = "21.0.0"
BOGUS_TOKEN = "mcrs_bogus_token_that_does_not_resolve"


def hello(host: str, port: int, protocol: str, timeout: float, auth: dict | None) -> dict:
    """1接続で hello を1回送り、応答オブジェクトを返す。"""
    params = {"protocol": protocol}
    if auth is not None:
        params["auth"] = auth
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.settimeout(timeout)
        reader = sock.makefile("rb")
        msg = {"jsonrpc": "2.0", "id": 1, "method": "hello", "params": params}
        sock.sendall((json.dumps(msg, separators=(",", ":")) + "\n").encode("utf-8"))
        raw = reader.readline()
        if not raw:
            raise RuntimeError("connection closed by server without a response")
        return json.loads(raw.decode("utf-8").rstrip("\r\n"))


def check(label: str, resp: dict, enforce: bool, want_reason: str) -> bool:
    """enforce=True なら error.data.reason==want_reason、False なら result 成功を要求。"""
    if enforce:
        err = resp.get("error")
        if not err:
            print(f"  FAIL [{label}] enforcement ON なのに成功してしまった: {resp.get('result')}")
            return False
        reason = err.get("data", {}).get("reason")
        if reason != want_reason:
            print(f"  FAIL [{label}] reason={reason!r} (want {want_reason!r}) code={err.get('code')}")
            return False
        print(f"  PASS [{label}] -> error reason={reason} code={err.get('code')}")
        return True
    # enforcement OFF: token 欠落/無効は許容され hello は成功するはず
    if "error" in resp:
        print(f"  FAIL [{label}] enforcement OFF なのに error: {resp['error']}")
        return False
    print(f"  PASS [{label}] -> success protocol={resp['result'].get('protocol')}")
    return True


def main() -> int:
    ap = argparse.ArgumentParser(description="McRemote hello auth-enforcement smoke test")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25575)
    ap.add_argument("--protocol", default=PROTOCOL)
    ap.add_argument("--timeout", type=float, default=10.0)
    ap.add_argument("--expect", choices=["on", "off"], required=True,
                    help="サーバの auth.enforcement 設定に合わせる")
    args = ap.parse_args()
    enforce = args.expect == "on"

    print(f"[hello_auth_test] expect enforcement={'ON' if enforce else 'OFF'} "
          f"target={args.host}:{args.port}")
    try:
        ok = True
        # 1) token 無し
        r1 = hello(args.host, args.port, args.protocol, args.timeout, auth=None)
        ok &= check("no-token", r1, enforce, "auth_required")
        # 2) 無効 token
        r2 = hello(args.host, args.port, args.protocol, args.timeout, auth={"token": BOGUS_TOKEN})
        ok &= check("bad-token", r2, enforce, "token_invalid")
    except (OSError, RuntimeError, json.JSONDecodeError, KeyError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2

    print("PASS: hello auth enforcement 否定パス" if ok else "FAIL: 期待と不一致")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
