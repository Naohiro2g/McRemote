#!/usr/bin/env python3
"""McRemote b6 candidate live-auto smoke test for world.setSign/world.getSign/world.updateSignLine
(Python standard library only).

The exact wire contract for all three is locked by DECISIONS 2026-08-26-05. This plugin is the
candidate that decision carries (McRemote codex/b6-set-sign@a34fec0); method-set state (shared
fixture, cross-client parity, formal evidence, release) is separate and still open. This script
exercises all three against a real Paper server. It reuses the live_auto.py connection harness
rather than duplicating it.

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

from live_auto import (
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
    if front[0] != {"text": "plain", "color": front[0]["color"], "decorations": []}:
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
        "world.setSign style line (named color + all 5 decorations)",
        rpc.call("world.setSign", [2, y, 5, {
            "front": [{
                "text": "styled", "color": "red",
                "decorations": ["bold", "italic", "underlined", "strikethrough", "obfuscated"],
            }, "", "", ""],
        }]),
    )
    styled_line = result(rpc.call("world.getSign", [2, y, 5]))["front"][0]
    expected_decorations = sorted(["bold", "italic", "underlined", "strikethrough", "obfuscated"])
    if styled_line != {"text": "styled", "color": "red", "decorations": expected_decorations}:
        raise AssertionError(f"getSign named-color style round-trip mismatch: {styled_line!r}")

    require_null_result(
        "world.setSign style line (hex color)",
        rpc.call("world.setSign", [2, y, 5, {
            "front": [{"text": "hexline", "color": "#123456"}, "", "", ""],
        }]),
    )
    hex_line = result(rpc.call("world.getSign", [2, y, 5]))["front"][0]
    if hex_line != {"text": "hexline", "color": "#123456", "decorations": []}:
        raise AssertionError(f"getSign hex-color round-trip mismatch: {hex_line!r}")

    require_null_result(
        "world.setSign mixed shorthand string and object lines",
        rpc.call("world.setSign", [2, y, 5, {
            "front": ["plain again", {"text": "bold only", "decorations": ["bold"]}, "", ""],
        }]),
    )
    mixed_front = result(rpc.call("world.getSign", [2, y, 5]))["front"]
    if mixed_front[0]["text"] != "plain again" or mixed_front[0]["decorations"] != []:
        raise AssertionError(f"getSign mixed line0 mismatch: {mixed_front[0]!r}")
    if mixed_front[1]["text"] != "bold only" or mixed_front[1]["decorations"] != ["bold"]:
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
        "world.setSign unknown decoration token",
        rpc.call("world.setSign", [2, y, 5, {
            "front": [{"text": "x", "decorations": ["glowing"]}, "", "", ""],
        }]),
        "invalid_property_value",
    )
    require_reason(
        "world.setSign non-array decorations",
        rpc.call("world.setSign", [2, y, 5, {
            "front": [{"text": "x", "decorations": "bold"}, "", "", ""],
        }]),
        "invalid_params",
    )

    print("PASS world.getSign / style setSign: default-line shape, named+hex color round-trip, "
          "all 5 decoration tokens, mixed string/object lines, not_a_sign, invalid color/decorations "
          "(sign_waxed and rendered-color truth need live-human)")


def verify_update_sign_line(rpc: Rpc, height: int) -> None:
    y = height + 1

    require_null_result(
        "updateSignLine sign block placement",
        rpc.call("world.setBlock", [3, y, 5, {"block_id": "oak_sign", "state": {"rotation": 0}}]),
    )
    require_null_result(
        "updateSignLine baseline front (4 distinct lines)",
        rpc.call("world.setSign", [3, y, 5, {"front": ["L0", "L1", "L2", "L3"]}]),
    )
    require_null_result(
        "updateSignLine baseline back",
        rpc.call("world.setSign", [3, y, 5, {"back": ["B0", "B1", "B2", "B3"]}]),
    )

    require_null_result(
        "world.updateSignLine patches only front line 1",
        rpc.call("world.updateSignLine", [3, y, 5, "front", 1, {
            "text": "patched", "color": "red", "decorations": ["bold"],
        }]),
    )
    patched = result(rpc.call("world.getSign", [3, y, 5]))
    front = patched["front"]
    if front[1] != {"text": "patched", "color": "red", "decorations": ["bold"]}:
        raise AssertionError(f"updateSignLine did not apply to the targeted line: {front[1]!r}")
    if front[0]["text"] != "L0" or front[2]["text"] != "L2" or front[3]["text"] != "L3":
        raise AssertionError(f"updateSignLine disturbed an untouched front line: {front!r}")
    back = patched["back"]
    if [line["text"] for line in back] != ["B0", "B1", "B2", "B3"]:
        raise AssertionError(f"updateSignLine disturbed the untouched back face: {back!r}")

    require_null_result(
        "world.updateSignLine on the back face",
        rpc.call("world.updateSignLine", [3, y, 5, "back", 2, "patched back"]),
    )
    back_after = result(rpc.call("world.getSign", [3, y, 5]))["back"]
    if back_after[2]["text"] != "patched back":
        raise AssertionError(f"updateSignLine did not patch the targeted back line: {back_after!r}")
    if [line["text"] for line in back_after] != ["B0", "B1", "patched back", "B3"]:
        raise AssertionError(f"updateSignLine disturbed an untouched back line: {back_after!r}")

    require_reason(
        "world.updateSignLine against a non-sign block",
        rpc.call("world.updateSignLine", [1, y, 5, "front", 0, "x"]),
        "not_a_sign",
    )
    require_reason(
        "world.updateSignLine invalid face",
        rpc.call("world.updateSignLine", [3, y, 5, "left", 0, "x"]),
        "invalid_params",
    )
    require_reason(
        "world.updateSignLine line_index out of range",
        rpc.call("world.updateSignLine", [3, y, 5, "front", 4, "x"]),
        "invalid_params",
    )

    print("PASS world.updateSignLine: patches exactly one line, leaves the rest of the face and "
          "the other face untouched, not_a_sign, invalid face/line_index "
          "(sign_waxed needs live-human)")


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
    parser.add_argument("--protocol", default="23.0.0")
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
        verify_update_sign_line(rpc, height)
        print("PASS: McRemote b6 world.setSign/world.getSign/world.updateSignLine live-auto candidate")
        return 0
    except (AssertionError, OSError, RuntimeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    finally:
        if rpc is not None:
            rpc.close()


if __name__ == "__main__":
    sys.exit(main())
