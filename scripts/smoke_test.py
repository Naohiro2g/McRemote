#!/usr/bin/env python3
"""McRemote raw-socket smoke test (Python standard library only).

minecraft-remote-api モジュールに依存しない素の疎通テスト。JSON-RPC 2.0 ワイヤ
（wire-format-design §3）で build-state 経路（プレイヤー/認証 不要）を流す:

    hello(build.dimension/origin) -> build.setDimension(dimension) -> build.setOrigin(ox,oy,oz)
        -> world.setBlock(x,y,z,{block_id,state}) -> world.getBlock(x,y,z)

set成功のresult:nullと、明示getBlockの構造化BlockValueを判定する。

ワイヤ枠（wire-format-design §2/§3）:
  - 直 TCP は 1行=1 JSON（compact, \n 終端）。
  - 要求 {"jsonrpc":"2.0","id":N,"method":...,"params":...}、応答 {... ,"id":N,"result"|"error":...}。
  - id を省くと notification ＝ 応答が返らない（FAST modeのworld.setBlock/setBlocks）。
  - hello は最初の1メッセージ（object params、build.dimension/originを含められる）。応答は flat result
    {protocol, mc_version, supported_mc_versions, world_constants:{y_sea}, catalogHash, ...}（§6.2）。非互換は error。

使い方（サーバを runServer 等で起動し、新プラグインを反映してから）:
  python3 scripts/smoke_test.py
  python3 scripts/smoke_test.py --host 127.0.0.1 --port 25575 \
      --protocol 23.0.0 \
      --dimension overworld --ox 200 --oy 0 --oz 200 \
      --x 0 --y 0 --z 0 --material gold_block
"""
import argparse
import json
import socket
import sys

# クライアントが要求する protocol semver（wire-format-design §6.1・clean な protocol 版）
PROTOCOL = "23.0.0"


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
    ap.add_argument("--material", default="gold_block")
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
            info = request("hello", {
                "protocol": args.protocol,
                "build": {
                    "dimension": args.dimension,
                    "origin": [args.ox, args.oy, args.oz],
                },
            })
            print(f"[hello]          <- {json.dumps(info, ensure_ascii=False)}")
            if not isinstance(info, dict):
                print(f"FAIL: hello result is not an object: {info!r}")
                return 1
            for key in ("protocol", "mc_version", "supported_mc_versions", "world_constants",
                        "catalogHash", "dimension", "origin"):
                if key not in info:
                    print(f"FAIL: hello result missing {key!r}: {info}")
                    return 1
            # y_sea は world_constants object に束ねられる（§6.2 / DECISIONS 2026-07-02-02）。
            wc = info["world_constants"]
            if not isinstance(wc, dict) or "y_sea" not in wc:
                print(f"FAIL: world_constants must be an object containing y_sea: {info}")
                return 1
            if "y_sea" in info:
                print(f"FAIL: y_sea must not be top-level; use world_constants.y_sea: {info}")
                return 1
            catalog_hash = info["catalogHash"]
            if not (isinstance(catalog_hash, str) and len(catalog_hash) == 64
                    and all(c in "0123456789abcdef" for c in catalog_hash)):
                print(f"FAIL: catalogHash must be SHA-256 hex for b3: {catalog_hash!r}")
                return 1
            print(f"                    protocol={info['protocol']} mc_version={info['mc_version']} "
                  f"supported={info['supported_mc_versions']} world_constants.y_sea={wc['y_sea']} "
                  f"catalogHash={info['catalogHash']}")
            expected_context = {
                "dimension": (args.dimension if ":" in args.dimension
                              else f"minecraft:{args.dimension}"),
                "origin": [args.ox, args.oy, args.oz],
            }
            if {key: info.get(key) for key in expected_context} != expected_context:
                print(f"FAIL: hello build context is not canonical: {info}")
                return 1

            # catalog 本体は認証後配送。token 無し hello が通る開発設定でも取得は拒否される。
            catalog_err = request_error("catalog.get", [])
            print(f"[catalog.get unauth] <- {json.dumps(catalog_err, ensure_ascii=False)}")
            if catalog_err.get("data", {}).get("reason") != "auth_required":
                print(f"FAIL: unauthenticated catalog.get must return auth_required: {catalog_err}")
                return 1

            # b4 player pose method は登録済みで、paired identity が無い場合は auth_required。
            pose_err = request_error("player.getPose", [])
            print(f"[player.getPose unauth] <- {json.dumps(pose_err, ensure_ascii=False)}")
            if pose_err.get("data", {}).get("reason") != "auth_required":
                print(f"FAIL: unauthenticated player.getPose must return auth_required: {pose_err}")
                return 1

            dimension_context = request("build.setDimension", [args.dimension])
            origin_context = request("build.setOrigin", [args.ox, args.oy, args.oz])
            print(f"[build.setDimension] <- {dimension_context}")
            print(f"[build.setOrigin]    <- {origin_context}")
            if dimension_context != expected_context or origin_context != expected_context:
                print("FAIL: build setters must return the same canonical context")
                return 1

            failures = []

            # (1) protocol 22 BlockSpecを送る → setter成功はexact null。
            placed = request("world.setBlock", [
                args.x, args.y, args.z,
                {"block_id": args.material, "state": {}},
            ])
            print(f"[setBlock]        <- {placed!r}")
            if placed is not None:
                failures.append(f"setBlock response must be null: {placed!r}")

            # (2) 適用後状態は明示getBlockで正準BlockValueとして観察する。
            got = request("world.getBlock", [args.x, args.y, args.z])
            print(f"[getBlock]        <- {got!r}")
            expected_stateless = {"block_id": "minecraft:gold_block", "state": {}}
            if got != expected_stateless:
                failures.append(f"getBlock mismatch: want={expected_stateless!r} got={got!r}")

            # (3) 短縮ID＋部分state入力。
            stateful = request("world.setBlock", [
                args.x, args.y + 1, args.z,
                {"block_id": "oak_log", "state": {"axis": "z"}},
            ])
            print(f"[setBlock state]  <- {stateful!r}")
            rt = request("world.getBlock", [args.x, args.y + 1, args.z])
            expected_log = {"block_id": "minecraft:oak_log", "state": {"axis": "z"}}
            if stateful is not None or rt != expected_log:
                failures.append(f"stateful round-trip failed: set={stateful!r} get={rt!r}")

            # (4) 複数property・順不同入力 → full state（default補完）。
            stairs = request("world.setBlock", [
                args.x, args.y + 2, args.z,
                {"block_id": "oak_stairs", "state": {
                    "waterlogged": True, "half": "top", "facing": "east",
                }},
            ])
            expected_stairs = {"block_id": "minecraft:oak_stairs", "state": {
                "facing": "east", "half": "top", "shape": "straight", "waterlogged": True,
            }}
            print(f"[setBlock states] <- {stairs!r}")
            got_stairs = request("world.getBlock", [args.x, args.y + 2, args.z])
            if stairs is not None or got_stairs != expected_stairs:
                failures.append(
                    f"multi-state canonical mismatch: want={expected_stairs!r} got={got_stairs!r}"
                )

            # (5) 数値stateをJSON number相当の表現で往復。
            wheat = request("world.setBlock", [
                args.x, args.y + 3, args.z,
                {"block_id": "wheat", "state": {"age": 3}},
            ])
            print(f"[setBlock number] <- {wheat!r}")
            got_wheat = request("world.getBlock", [args.x, args.y + 3, args.z])
            expected_wheat = {"block_id": "minecraft:wheat", "state": {"age": 3}}
            if wheat is not None or got_wheat != expected_wheat:
                failures.append(f"numeric state mismatch: {got_wheat!r}")

            flushed = request("connection.flush", [])
            print(f"[connection.flush] <- {flushed!r}")
            if flushed is not None:
                failures.append(f"connection.flush response must be null: {flushed!r}")

            # (6) 未知ブロックはerror＋data.block_id。data.refは使用しない。
            err = request_error("world.setBlock", [
                args.x, args.y, args.z,
                {"block_id": "definitely_not_a_block", "state": {}},
            ])
            print(f"[setBlock bad]    <- {json.dumps(err, ensure_ascii=False)}")
            if err.get("data", {}).get("reason") != "unknown_block":
                failures.append(f"expected unknown_block, got {err}")
            if err.get("data", {}).get("block_id") != "minecraft:definitely_not_a_block":
                failures.append(f"unknown_block missing canonical block_id: {err}")
            if "ref" in err.get("data", {}):
                failures.append(f"protocol 22 must not emit data.ref: {err}")

            # (7) property名と値のエラーを分離。値エラーはcatalog由来のallowedを必須化。
            unknown_prop = request_error("world.setBlock", [
                args.x, args.y, args.z,
                {"block_id": "stone", "state": {"axis": "y"}},
            ])
            print(f"[unknown property] <- {json.dumps(unknown_prop, ensure_ascii=False)}")
            if unknown_prop.get("data", {}).get("reason") != "unknown_property":
                failures.append(f"expected unknown_property, got {unknown_prop}")

            invalid_value = request_error("world.setBlock", [
                args.x, args.y, args.z,
                {"block_id": "oak_log", "state": {"axis": "w"}},
            ])
            print(f"[invalid value]   <- {json.dumps(invalid_value, ensure_ascii=False)}")
            invalid_data = invalid_value.get("data", {})
            if invalid_data.get("reason") != "invalid_property_value":
                failures.append(f"expected invalid_property_value, got {invalid_value}")
            if invalid_data.get("allowed") != ["x", "y", "z"]:
                failures.append(f"expected allowed=[x,y,z], got {invalid_data.get('allowed')!r}")

            # (8) 固定params個数と座標エラーは block ref ではなく invalid_params。
            extra_arg = request_error(
                "world.setBlock", [args.x, args.y, args.z,
                                   {"block_id": "stone", "state": {}}, "unexpected"]
            )
            if extra_arg.get("data", {}).get("reason") != "invalid_params":
                failures.append(f"extra arg must be invalid_params: {extra_arg}")
            bad_coord = request_error("world.setBlock", [
                "not-a-number", args.y, args.z, {"block_id": "stone", "state": {}},
            ])
            if bad_coord.get("data", {}).get("reason") != "invalid_params":
                failures.append(f"bad coordinate must be invalid_params: {bad_coord}")

            # (9) exact shape／型／旧文字列union拒否。
            malformed_specs = [
                "stone",
                {"block_id": "stone"},
                {"block_id": "stone", "state": None},
                {"block_id": "stone", "state": {}, "ref": "stone"},
                {"block_id": "oak_log", "state": {"axis": ["z"]}},
            ]
            for malformed in malformed_specs:
                malformed_error = request_error(
                    "world.setBlock", [args.x, args.y, args.z, malformed]
                )
                if malformed_error.get("data", {}).get("reason") != "invalid_params":
                    failures.append(f"malformed BlockSpec accepted: {malformed!r} -> {malformed_error}")

            print()
            if not failures:
                print("PASS: catalog gate + canonical states + detailed state/params errors")
                return 0
            for f in failures:
                print(f"FAIL: {f}")
            return 1
    except (OSError, RuntimeError, json.JSONDecodeError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
