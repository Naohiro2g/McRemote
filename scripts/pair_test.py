#!/usr/bin/env python3
"""McRemote pairing smoke test (Python standard library only).

b2 認証マイルストーン1の疎通確認（wire-format-design §6.5）。enforcement OFF の
まま pair フローを1周させる:

    auth.pairBegin -> (人間が /mcremote pair <code> をゲーム内で実行) -> auth.pairPoll
        -> token 取得 -> hello(auth:{token})

pairing は hello の前段の独立メソッド（§6.5）。pairBegin は pairing_id と6桁 pair_code
を即返し、pairPoll は pending -> ok{token} を返す（失敗は pair_expired / pair_not_found）。
token は hash のみサーバ保存。enforcement OFF ゆえ hello は token 無しでも通るが、本テストは
発行された token が実在することを示す。

使い方（サーバ起動＋新プラグイン反映、対象プレイヤーが在線の状態で）:
  python3 scripts/pair_test.py
  # 表示された6桁コードを、ゲーム内で対象プレイヤーが実行:
  #   /mcremote pair <code>
"""
import argparse
import base64
import json
import shutil
import socket
import subprocess
import sys
import time

PROTOCOL = "21.0.0"


def copy_to_clipboard(text: str) -> None:
    """クリップボードへコピー（best-effort）。OS ツール優先（Wayland→X11）→ OSC 52 fallback。"""
    for cmd in (["wl-copy"], ["xclip", "-selection", "clipboard"],
                ["xsel", "--clipboard", "--input"]):
        if shutil.which(cmd[0]):
            try:
                subprocess.run(cmd, input=text.encode("utf-8"), check=True)
                print(f"  (clipboard: {cmd[0]} でコピー / copied)")
                return
            except (OSError, subprocess.SubprocessError):
                continue
    # fallback: OSC 52（端末が許可していれば効く・不可視）
    b64 = base64.b64encode(text.encode("utf-8")).decode("ascii")
    sys.stdout.write(f"\033]52;c;{b64}\a")
    sys.stdout.flush()
    print("  (clipboard: OSC 52 を試行・端末依存 / OSC 52 attempted, terminal-dependent)")


def main() -> int:
    ap = argparse.ArgumentParser(description="McRemote pairing smoke test")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25575)
    ap.add_argument("--protocol", default=PROTOCOL)
    ap.add_argument("--token-type", default="session", choices=["session", "player"])
    ap.add_argument("--device", default=None, help="optional device label (player_token)")
    ap.add_argument("--poll-interval", type=float, default=1.5, help="pairPoll 間隔 (~1-2s)")
    ap.add_argument("--timeout", type=float, default=10.0, help="socket read timeout")
    ap.add_argument("--clipboard", action="store_true",
                    help="OSC 52 でコマンドをクリップボードへコピー（対応端末のみ）")
    args = ap.parse_args()

    try:
        with socket.create_connection((args.host, args.port), timeout=args.timeout) as sock:
            sock.settimeout(args.timeout)
            reader = sock.makefile("rb")
            next_id = [0]

            def recv_obj() -> dict:
                raw = reader.readline()
                if not raw:
                    raise RuntimeError("connection closed by server")
                return json.loads(raw.decode("utf-8").rstrip("\r\n"))

            def call(method: str, params):
                next_id[0] += 1
                rid = next_id[0]
                msg = {"jsonrpc": "2.0", "id": rid, "method": method, "params": params}
                sock.sendall((json.dumps(msg, separators=(",", ":")) + "\n").encode("utf-8"))
                resp = recv_obj()
                if resp.get("id") != rid:
                    raise RuntimeError(f"{method} -> id mismatch: want {rid}, got {resp.get('id')}")
                return resp

            # 1) auth.pairBegin（pre-hello 独立メソッド）
            begin_params = {"token_type": args.token_type,
                            "client": {"name": "pair_test.py", "version": "0", "locale": "ja"}}
            if args.device:
                begin_params["device"] = args.device
            begin = call("auth.pairBegin", begin_params)
            if "error" in begin:
                print(f"FAIL: auth.pairBegin -> error {begin['error']}")
                return 1
            result = begin["result"]
            pairing_id = result["pairing_id"]
            pair_code = result["pair_code"]
            expires_in = result["expires_in"]
            grouped = f"{pair_code[:3]}-{pair_code[3:]}"
            command = f"/mcremote pair {grouped}"
            print(f"[pairBegin] pairing_id={pairing_id} expires_in={expires_in}s")
            print()
            print("  ── ペアリング / Pairing ─────────────────────────────")
            print("    チャットに貼付 / paste into chat:")
            print(f"      {command}")
            print("    （区切り不要・半角数字 / ASCII digits, separators optional）")
            print(f"    コピー用 / copy: {command}")
            print("  ────────────────────────────────────────────────────")
            if args.clipboard:
                copy_to_clipboard(command)
            print()

            # 2) auth.pairPoll を pending の間くり返す
            deadline = time.monotonic() + expires_in
            token = None
            while time.monotonic() < deadline:
                poll = call("auth.pairPoll", {"pairing_id": pairing_id})
                if "error" in poll:
                    reason = poll["error"].get("data", {}).get("reason")
                    print(f"FAIL: auth.pairPoll -> error reason={reason}")
                    return 1
                status = poll["result"].get("status")
                if status == "pending":
                    print("[pairPoll]  pending ... (待機中)")
                    time.sleep(args.poll_interval)
                    continue
                if status == "ok":
                    token = poll["result"]["token"]
                    print(f"[pairPoll]  ok -> token={token[:12]}… ({len(token)} chars)")
                    break
                print(f"FAIL: unexpected pairPoll status: {status!r}")
                return 1

            if token is None:
                print("FAIL: timed out waiting for /mcremote pair (pair_code expired)")
                return 1

            # 3) hello(auth:{token})。token を検証し UUID を束縛（§6.1/§6.2）。enforcement OFF でも
            #    解決できれば player/permissions を返す。ON では token 必須（欠落=auth_required/無効=token_invalid）。
            hello = call("hello", {"protocol": args.protocol, "auth": {"token": token}})
            if "error" in hello:
                print(f"FAIL: hello -> error {hello['error']}")
                return 1
            info = hello["result"]
            print(f"[hello]     <- protocol={info.get('protocol')} mc_version={info.get('mc_version')}")
            world_constants = info.get("world_constants")
            if not isinstance(world_constants, dict) or "y_sea" not in world_constants:
                print(f"FAIL: hello world_constants must contain y_sea: {info}")
                return 1
            if "y_sea" in info:
                print(f"FAIL: y_sea must not be top-level; use world_constants.y_sea: {info}")
                return 1
            if "catalogHash" not in info or info.get("catalogHash") is not None:
                print(f"FAIL: catalogHash must be present and null for b2: {info}")
                return 1
            print(f"[hello]     <- world_constants.y_sea={world_constants.get('y_sea')} catalogHash={info.get('catalogHash')}")
            print(f"[hello]     <- player={info.get('player')} permissions={info.get('permissions')}")
            if info.get("player") is None:
                print("WARN: hello に player が無い（token 未束縛）。enforcement OFF かつ store 失効の可能性。")
            print()
            print("PASS: pairBegin -> /mcremote pair -> pairPoll -> token -> hello 1周")
            return 0
    except (OSError, RuntimeError, json.JSONDecodeError, KeyError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
