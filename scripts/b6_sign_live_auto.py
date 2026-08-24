#!/usr/bin/env python3
"""McRemote b6 candidate live-auto smoke test for world.setSign (Python standard library only).

world.setSign is not yet a ratified b6 wire contract (DECISIONS 2026-08-16-06 names the scope
without fixing params/result shape); this script exercises the plain-text (PUT-style, no merge)
implementation candidate against a real Paper server. It reuses the b5_live_auto.py connection
harness rather than duplicating it.

Scope note: there is no world.getSign (no reader for sign text exists yet), so this script can
only assert on RPC-level success/failure, not on the sign's actual rendered text. sign_waxed is
not exercised here — waxing a sign requires an in-game honeycomb interaction (live-human), which
this script cannot perform; that branch is untested here, not "passing", and needs a live-human
run to cover. Physical placement variants (wall/hanging/wall_hanging sign) are not separately
exercised: SignCommands' own logic only depends on the block being `instanceof Sign`, which is
uniform across all placement forms.
"""

import argparse
import sys

from b5_live_auto import (
    Rpc,
    acquire_interactive_token,
    connect,
    require_null_result,
    require_reason,
    result,
)


def verify_sign(rpc: Rpc, height: int) -> None:
    y = height + 1

    # A real, unwaxed standing sign to exercise the success path against.
    require_null_result(
        "sign block placement",
        rpc.call("world.setBlock", [0, y, 5, {
            "block_id": "oak_sign", "state": {"rotation": 0},
        }]),
    )

    require_null_result(
        "world.setSign front only",
        rpc.call("world.setSign", [0, y, 5, {
            "front": ["Line 1", "Line 2", "", ""],
        }]),
    )
    require_null_result(
        "world.setSign back only",
        rpc.call("world.setSign", [0, y, 5, {
            "back": ["Back 1", "", "", ""],
        }]),
    )
    require_null_result(
        "world.setSign both faces independently",
        rpc.call("world.setSign", [0, y, 5, {
            "front": ["", "", "", ""],
            "back": ["", "", "", ""],
        }]),
    )

    require_reason(
        "world.setSign against a non-sign block",
        rpc.call("world.setSign", [1, y, 5, {"front": ["a", "", "", ""]}]),
        "not_a_sign",
    )

    require_reason(
        "world.setSign neither face given",
        rpc.call("world.setSign", [0, y, 5, {}]),
        "invalid_params",
    )
    require_reason(
        "world.setSign wrong line count",
        rpc.call("world.setSign", [0, y, 5, {"front": ["a", "b", "c"]}]),
        "invalid_params",
    )
    require_reason(
        "world.setSign non-string line",
        rpc.call("world.setSign", [0, y, 5, {"front": ["a", 1, "c", "d"]}]),
        "invalid_params",
    )
    require_reason(
        "world.setSign control character in line",
        rpc.call("world.setSign", [0, y, 5, {"front": ["a\nb", "", "", ""]}]),
        "invalid_params",
    )
    require_reason(
        "world.setSign line exceeds 64 code points",
        rpc.call("world.setSign", [0, y, 5, {"front": ["x" * 65, "", "", ""]}]),
        "invalid_params",
    )
    require_reason(
        "world.setSign unknown spec field",
        rpc.call("world.setSign", [0, y, 5, {"side": ["a", "", "", ""]}]),
        "invalid_params",
    )

    print("PASS world.setSign: front/back independence, not_a_sign, invalid_params branches "
          "(sign_waxed untested here, needs live-human honeycomb step)")


def main() -> int:
    parser = argparse.ArgumentParser(description="McRemote b6 world.setSign live-auto candidate")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--protocol", default="22.0.0")
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument(
        "--interactive-pair",
        action="store_true",
        help="pair once in Minecraft and keep the session token in memory only",
    )
    args = parser.parse_args()

    rpc = None
    try:
        token = acquire_interactive_token(args) if args.interactive_pair else None
        rpc = connect(args, token)
        height = result(rpc.call("world.getHeight", [0, 0]))
        if not isinstance(height, int):
            raise AssertionError(f"height must be integer: {height!r}")
        verify_sign(rpc, height)
        print("PASS: McRemote b6 world.setSign live-auto candidate")
        return 0
    except (AssertionError, OSError, RuntimeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    finally:
        if rpc is not None:
            rpc.close()


if __name__ == "__main__":
    sys.exit(main())
