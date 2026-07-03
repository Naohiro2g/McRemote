#!/usr/bin/env python3
"""McRemote raw-socket smoke test (Python standard library only).

minecraft-remote-api モジュールに依存しない素の疎通テスト。JSON-RPC 2.0 ワイヤ
（wire-format-design §3）で build-state 経路（プレイヤー/認証 不要）を流す:

    hello -> build.setWorld(dimension) -> build.setOrigin(ox,oy,oz)
        -> world.setBlock(x,y,z,material) -> world.getBlock(x,y,z)

最後に getBlock の戻りが material と一致するかを判定する。

ワイヤ枠（wire-format-design §2/§3）:
  - 直 TCP は 1行=1 JSON（compact, \n 終端）。
  - 要求 {"jsonrpc":"2.0","id":N,"method":...,"params":...}、応答 {... ,"id":N,"result"|"error":...}。
  - id を省くと notification ＝ 応答が返らない（world.setBlock は既定 send-only）。
  - hello は最初の1メッセージ（object params {"protocol":"21.0.0"}）。応答は flat result
    {protocol, mc_version, supported_mc_versions, world_constants:{y_sea}, catalogHash, ...}（§6.2）。非互換は error。

使い方（サーバを runServer 等で起動し、新プラグインを反映してから）:
  python3 scripts/smoke_test.py
  python3 scripts/smoke_test.py --host 127.0.0.1 --port 25575 \
      --protocol 21.0.0 \
      --dimension overworld --ox 200 --oy 0 --oz 200 \
      --x 0 --y 0 --z 0 --material GOLD_BLOCK
"""
import argparse
import json
import socket
import sys

# クライアントが要求する protocol semver（wire-format-design §6.1・clean な protocol 版）
PROTOCOL = "21.0.0"


def main() -> int:
    ap = argparse.ArgumentParser(description="McRemote JSON-RPC smoke test")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25575)
    ap.add_argument("--protocol", default=PROTOCOL, help="client protocol for hello")
    ap.add_argument("--dimension", default="overworld")
    ap.add_argument("--ox", type=int, default=200, help="build origin x")
    ap.add_argument("--oy", type=int, default=0, help="build origin y")
    ap.add_argument("--oz", type=int, default=200, help="build origin z")
    ap.add_argument("--x", type=int, default=0, help="block x relative to origin")
    ap.add_argument("--y", type=int, default=0, help="block y relative to origin")
    ap.add_argument("--z", type=int, default=0, help="block z relative to origin")
    ap.add_argument("--material", default="GOLD_BLOCK")
    ap.add_argument("--timeout", type=float, default=5.0)
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

            def request(method: str, params):
                """id 付き要求 → result を返す。error なら例外。"""
                next_id[0] += 1
                rid = next_id[0]
                msg = {"jsonrpc": "2.0", "id": rid, "method": method, "params": params}
                sock.sendall((json.dumps(msg, separators=(",", ":")) + "\n").encode("utf-8"))
                resp = recv_obj()
                if "error" in resp:
                    raise RuntimeError(f"{method} -> error {resp['error']}")
                if resp.get("id") != rid:
                    raise RuntimeError(f"{method} -> id mismatch: want {rid}, got {resp.get('id')}")
                return resp.get("result")

            def request_error(method: str, params) -> dict:
                """id 付き要求 → error オブジェクトを返す。result が返ったら例外。"""
                next_id[0] += 1
                rid = next_id[0]
                msg = {"jsonrpc": "2.0", "id": rid, "method": method, "params": params}
                sock.sendall((json.dumps(msg, separators=(",", ":")) + "\n").encode("utf-8"))
                resp = recv_obj()
                if "error" not in resp:
                    raise RuntimeError(f"{method} -> expected error, got result {resp.get('result')!r}")
                return resp["error"]

            # hello（object params・最初の1メッセージ）
            info = request("hello", {"protocol": args.protocol})
            print(f"[hello]          <- {json.dumps(info, ensure_ascii=False)}")
            if not isinstance(info, dict):
                print(f"FAIL: hello result is not an object: {info!r}")
                return 1
            for key in ("protocol", "mc_version", "supported_mc_versions", "world_constants", "catalogHash"):
                if key not in info:
                    print(f"FAIL: hello result missing {key!r}: {info}")
                    return 1
            # y_sea は world_constants object に束ねられる（§6.2 / DECISIONS 2026-07-02-02）。
            wc = info["world_constants"]
            if not isinstance(wc, dict) or "y_sea" not in wc:
                print(f"FAIL: world_constants must be an object containing y_sea: {info}")
                return 1
            print(f"                    protocol={info['protocol']} mc_version={info['mc_version']} "
                  f"supported={info['supported_mc_versions']} world_constants.y_sea={wc['y_sea']} "
                  f"catalogHash={info['catalogHash']}")

            print(f"[build.setWorld]  <- {request('build.setWorld', [args.dimension])}")
            print(f"[build.setOrigin] <- {request('build.setOrigin', [args.ox, args.oy, args.oz])}")

            failures = []

            # (1) setBlock を id 付き要求で送る → 設置後の canonical を同期応答（§7.3）。
            placed = request("world.setBlock", [args.x, args.y, args.z, args.material])
            print(f"[setBlock]        <- {placed!r}")
            if not (isinstance(placed, str) and placed.startswith("minecraft:")):
                failures.append(f"setBlock response not canonical: {placed!r}")

            # (2) getBlock は canonical-full block_state_ref（§7.1）。round-trip 文字列等価を要求。
            got = request("world.getBlock", [args.x, args.y, args.z])
            print(f"[getBlock]        <- {got!r}")
            if got != placed:
                failures.append(f"round-trip mismatch: setBlock={placed!r} getBlock={got!r}")

            # (3) state 付き＋prop ソートの確認（oak_log[axis=z] → minecraft:oak_log[axis=z]）。
            stateful = request("world.setBlock", [args.x, args.y + 1, args.z, "oak_log[axis=z]"])
            print(f"[setBlock state]  <- {stateful!r}")
            rt = request("world.getBlock", [args.x, args.y + 1, args.z])
            if not (isinstance(stateful, str) and "axis=z" in stateful and rt == stateful):
                failures.append(f"stateful round-trip failed: set={stateful!r} get={rt!r}")

            # (4) 未知ブロックは error + data.reason=unknown_block（§7.3）。
            err = request_error("world.setBlock", [args.x, args.y, args.z, "definitely_not_a_block"])
            print(f"[setBlock bad]    <- {json.dumps(err, ensure_ascii=False)}")
            if err.get("data", {}).get("reason") != "unknown_block":
                failures.append(f"expected unknown_block, got {err}")

            print()
            if not failures:
                print("PASS: setBlock response + canonical round-trip + reason error")
                return 0
            for f in failures:
                print(f"FAIL: {f}")
            return 1
    except (OSError, RuntimeError, json.JSONDecodeError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())