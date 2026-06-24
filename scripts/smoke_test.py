#!/usr/bin/env python3
"""McRemote raw-socket smoke test (Python standard library only).

minecraft-remote-api モジュールに依存しない素の疎通テスト。
新しい build-state 経路（プレイヤー/認証 不要）を流す:

    setWorld(dimension) -> setBuildOrigin(ox,oy,oz)
        -> world.setBlock(x,y,z,material) -> world.getBlock(x,y,z)

最後に getBlock の戻りが material と一致するかを判定する。

プロトコル枠（McRemote 現状）:
  - コマンドは "name(arg,arg,...)\n" の1行。
  - 応答は send() ごとに1行（末尾 \n）。
  - setWorld/setBuildOrigin/getBlock は1行返す。
  - world.setBlock は成功時は無応答（エラー時のみ "Error:..." を返す）。

使い方（サーバを runServer 等で起動し、新プラグインを反映してから）:
  python3 scripts/smoke_test.py
  python3 scripts/smoke_test.py --host 127.0.0.1 --port 25575 \
      --dimension overworld --ox 200 --oy 0 --oz 200 \
      --x 0 --y 0 --z 0 --material GOLD_BLOCK
"""
import argparse
import socket
import sys


def main() -> int:
    ap = argparse.ArgumentParser(description="McRemote raw-socket smoke test")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25575)
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

            def send(cmd: str) -> None:
                sock.sendall((cmd + "\n").encode("utf-8"))

            def recv_line() -> str:
                raw = reader.readline()
                if not raw:
                    raise RuntimeError("connection closed by server")
                return raw.decode("utf-8").rstrip("\r\n")

            send(f"setWorld({args.dimension})")
            print(f"[setWorld]       <- {recv_line()}")

            send(f"setBuildOrigin({args.ox},{args.oy},{args.oz})")
            print(f"[setBuildOrigin] <- {recv_line()}")

            # setBlock は成功時に無応答（エラーなら "Error:..." が返る）
            send(f"world.setBlock({args.x},{args.y},{args.z},{args.material})")
            print(f"[setBlock]       -> world.setBlock({args.x},{args.y},{args.z},{args.material})")

            send(f"world.getBlock({args.x},{args.y},{args.z})")
            got = recv_line()
            print(f"[getBlock]       <- {got}")

            ok = got == args.material
            print()
            if ok:
                print(f"PASS: getBlock == {args.material}")
                return 0
            print(f"FAIL: expected {args.material}, got {got!r}")
            return 1
    except (OSError, RuntimeError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())