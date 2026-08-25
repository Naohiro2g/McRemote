#!/usr/bin/env python3
"""McRemote b6 candidate live-auto smoke test for world.setSign/world.getSign (Python standard
library only).

Neither method is a ratified b6 wire contract yet (DECISIONS 2026-08-16-06 names the sign scope
without fixing params/result shape; the getSign shape and the setSign color/bold/italic style
extension are a 2026-08-25 design-session candidate, see local NOTES_ja.md). This script exercises
both against a real Paper server. It reuses the b5_live_auto.py connection harness rather than
duplicating it.

Scope note: sign_waxed is not exercised here — waxing a sign requires an in-game honeycomb
interaction (live-human), which this script cannot perform; that branch is untested here, not
"passing", and needs a live-human run to cover. Physical placement variants (wall/hanging/
wall_hanging sign) are not separately exercised: SignCommands' own logic only depends on the block
being `instanceof Sign`, which is uniform across all placement forms. This script also cannot
confirm the *visual* rendered color of an unstyled line (DEFAULT_COLOR_TOKEN="black" in
SignCommands.java) — it can only observe what our own encoder reports for an unset Adventure
TextColor, which is a fixed convention in the code, not evidence of the client's actual rendering.
Confirming the rendered color against a live client is a live-human task.
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


def verify_get_sign_and_style(rpc: Rpc, height: int) -> None:
    y = height + 1

    require_null_result(
        "style sign block placement",
        rpc.call("world.setBlock", [2, y, 5, {"block_id": "oak_sign", "state": {"rotation": 0}}]),
    )

    require_null_result(
        "world.setSign plain line for getSign default check",
        rpc.call("world.setSign", [2, y, 5, {"front": ["plain", "", "", ""]}]),
    )
    plain_sign = result(rpc.call("world.getSign", [2, y, 5]))
    front = plain_sign["front"]
    back = plain_sign["back"]
    if front[0] != {"text": "plain", "color": front[0]["color"], "bold": False, "italic": False}:
        raise AssertionError(f"getSign plain line mismatch: {front[0]!r}")
    for i in range(1, 4):
        if front[i]["text"] != "":
            raise AssertionError(f"getSign blank line mismatch: {front[i]!r}")
    if len(back) != 4:
        raise AssertionError(f"getSign back shape mismatch: {plain_sign!r}")
    if plain_sign["waxed"] is not False:
        raise AssertionError(f"getSign waxed mismatch: {plain_sign!r}")
    print(f"OBSERVED encoder default color token for an unset line: {front[0]['color']!r} "
          f"(convention in code, not a live-verified rendering fact — needs live-human)")

    require_null_result(
        "world.setSign style line (named color + bold + italic)",
        rpc.call("world.setSign", [2, y, 5, {
            "front": [{"text": "styled", "color": "red", "bold": True, "italic": True}, "", "", ""],
        }]),
    )
    styled_line = result(rpc.call("world.getSign", [2, y, 5]))["front"][0]
    if styled_line != {"text": "styled", "color": "red", "bold": True, "italic": True}:
        raise AssertionError(f"getSign named-color style round-trip mismatch: {styled_line!r}")

    require_null_result(
        "world.setSign style line (hex color)",
        rpc.call("world.setSign", [2, y, 5, {
            "front": [{"text": "hexline", "color": "#123456"}, "", "", ""],
        }]),
    )
    hex_line = result(rpc.call("world.getSign", [2, y, 5]))["front"][0]
    if hex_line != {"text": "hexline", "color": "#123456", "bold": False, "italic": False}:
        raise AssertionError(f"getSign hex-color round-trip mismatch: {hex_line!r}")

    require_null_result(
        "world.setSign mixed shorthand string and object lines",
        rpc.call("world.setSign", [2, y, 5, {
            "front": ["plain again", {"text": "bold only", "bold": True}, "", ""],
        }]),
    )
    mixed_front = result(rpc.call("world.getSign", [2, y, 5]))["front"]
    if mixed_front[0]["text"] != "plain again" or mixed_front[0]["bold"] is not False:
        raise AssertionError(f"getSign mixed line0 mismatch: {mixed_front[0]!r}")
    if mixed_front[1]["text"] != "bold only" or mixed_front[1]["bold"] is not True:
        raise AssertionError(f"getSign mixed line1 mismatch: {mixed_front[1]!r}")

    require_reason(
        "world.getSign against a non-sign block",
        rpc.call("world.getSign", [1, y, 5]),
        "not_a_sign",
    )
    require_reason(
        "world.setSign unknown color token",
        rpc.call("world.setSign", [2, y, 5, {
            "front": [{"text": "x", "color": "not_a_color"}, "", "", ""],
        }]),
        "invalid_property_value",
    )
    require_reason(
        "world.setSign non-boolean bold",
        rpc.call("world.setSign", [2, y, 5, {
            "front": [{"text": "x", "bold": "yes"}, "", "", ""],
        }]),
        "invalid_params",
    )

    print("PASS world.getSign / style setSign: default-line shape, named+hex color round-trip, "
          "mixed string/object lines, not_a_sign, invalid color/bold "
          "(sign_waxed and rendered-color truth need live-human)")


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
        verify_get_sign_and_style(rpc, height)
        print("PASS: McRemote b6 world.setSign/world.getSign live-auto candidate")
        return 0
    except (AssertionError, OSError, RuntimeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    finally:
        if rpc is not None:
            rpc.close()


if __name__ == "__main__":
    sys.exit(main())
