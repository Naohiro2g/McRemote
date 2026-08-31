#!/usr/bin/env python3
"""Protocol 23.1 b7 live-auto runner.

This runner performs no deployment and never retries a side-effecting request.
It communicates with the non-production B7LiveProbe through its local data
directory. Run it on the Paper host (or with that directory mounted locally).
"""

import argparse
from decimal import Decimal
import json
import math
import os
from pathlib import Path
import sys
import time
import uuid

from live_auto import Rpc, acquire_interactive_token, reason, result


PROTOCOL = "23.1.0"
HANDLE_PREFIX = "mcr_eh_"


def require_reason(label, response, expected):
    actual = reason(response)
    if actual != expected:
        raise AssertionError(f"{label}: expected {expected}, got {response}")
    print(f"PASS {label}: {actual}")


def require_null(label, response):
    if "error" in response or response.get("result", object()) is not None:
        raise AssertionError(f"{label}: expected result:null, got {response}")
    print(f"PASS {label}: result:null")


def direction_value(label, value):
    if not isinstance(value, list) or len(value) != 3:
        raise AssertionError(f"{label}: not DirectionValue: {value!r}")
    numbers = []
    for item in value:
        if isinstance(item, bool) or not isinstance(item, (int, float)) or not math.isfinite(item):
            raise AssertionError(f"{label}: non-finite component: {value!r}")
        decimal = Decimal(str(item))
        if decimal.as_tuple().exponent < -6:
            raise AssertionError(f"{label}: more than 6 fractional digits: {value!r}")
        numbers.append(float(item))
    norm = math.sqrt(sum(component * component for component in numbers))
    if abs(norm - 1.0) > 1.5e-6:
        raise AssertionError(f"{label}: norm {norm} outside tolerance: {value!r}")
    return value


def _unescape_property(value):
    result_chars = []
    index = 0
    while index < len(value):
        if value[index] != "\\" or index + 1 >= len(value):
            result_chars.append(value[index])
            index += 1
            continue
        escaped = value[index + 1]
        if escaped == "u" and index + 5 < len(value):
            result_chars.append(chr(int(value[index + 2:index + 6], 16)))
            index += 6
        else:
            result_chars.append({"t": "\t", "n": "\n", "r": "\r"}.get(escaped, escaped))
            index += 2
    return "".join(result_chars)


def read_properties(path):
    values = {}
    for raw in path.read_text(encoding="iso-8859-1").splitlines():
        line = raw.strip()
        if not line or line[0] in "#!":
            continue
        escaped = False
        split_at = None
        for index, character in enumerate(line):
            if not escaped and character in "=:":
                split_at = index
                break
            escaped = character == "\\" and not escaped
            if character != "\\":
                escaped = False
        if split_at is None:
            values[_unescape_property(line)] = ""
        else:
            values[_unescape_property(line[:split_at].strip())] = _unescape_property(
                line[split_at + 1:].strip())
    return values


class ProbeController:
    def __init__(self, directory, timeout=15.0, monotonic=time.monotonic, sleep=time.sleep):
        self.directory = Path(directory)
        self.control = self.directory / "control.properties"
        self.observation = self.directory / "observation.properties"
        self.timeout = timeout
        self.monotonic = monotonic
        self.sleep = sleep
        self.sequence = time.time_ns()

    def issue(self, action, run_id=None, wait_status="complete", **fields):
        self.sequence += 1
        run_id = run_id or f"b7-{self.sequence}"
        values = {"schema": "1", "sequence": str(self.sequence), "run_id": run_id,
                  "action": action}
        for key, value in fields.items():
            output_key = key
            for prefix in ("target", "entity", "destination", "min", "max"):
                if key in {f"{prefix}_x", f"{prefix}_y", f"{prefix}_z"}:
                    output_key = key.replace("_", ".", 1)
            values[output_key] = str(value).lower() if isinstance(value, bool) else str(value)
        self.directory.mkdir(parents=True, exist_ok=True)
        temporary = self.control.with_suffix(".properties.tmp")
        temporary.write_text("".join(f"{key}={value}\n" for key, value in sorted(values.items())),
                             encoding="utf-8")
        os.replace(temporary, self.control)
        deadline = self.monotonic() + self.timeout
        while self.monotonic() < deadline:
            if self.observation.is_file():
                observed = read_properties(self.observation)
                if observed.get("sequence") == str(self.sequence):
                    if observed.get("status") == "error":
                        raise RuntimeError(f"probe rejected {action}: {observed.get('error')}")
                    if observed.get("status") == wait_status:
                        return observed
            self.sleep(0.05)
        raise TimeoutError(f"probe timed out waiting for {action}/{wait_status}")

    def wait_complete(self, sequence):
        deadline = self.monotonic() + self.timeout
        while self.monotonic() < deadline:
            if self.observation.is_file():
                observed = read_properties(self.observation)
                if observed.get("sequence") == str(sequence):
                    if observed.get("status") == "error":
                        raise RuntimeError(f"probe failed: {observed.get('error')}")
                    if observed.get("status") == "complete":
                        return observed
            self.sleep(0.05)
        raise TimeoutError("probe timed out waiting for later-tick observation")


def rpc_connect(args, token):
    rpc = Rpc(args.host, args.port, args.timeout)
    try:
        hello = {"protocol": PROTOCOL, "build": {
            "dimension": args.dimension,
            "origin": list(args.origin),
        }}
        if token:
            hello["auth"] = {"token": token}
        response = result(rpc.call("hello", hello))
        if response.get("protocol") != PROTOCOL:
            raise AssertionError(f"hello protocol mismatch: {response!r}")
        return rpc
    except BaseException:
        rpc.close()
        raise


def set_block(rpc, relative, block_id, state=None):
    require_null(f"setBlock {relative}", rpc.call("world.setBlock", [*relative, {
        "block_id": block_id, "state": state or {},
    }]))


def snapshot(probe, args, label, entity_points=""):
    ox, oy, oz = args.origin
    return probe.issue("snapshot", run_id=label, world=args.dimension,
                       block_points="", entity_points=entity_points,
                       **{"target_x": ox, "target_y": oy, "target_z": oz})


def verify_directions(rpc, probe, args):
    before_pos = result(rpc.call("player.getPos", []))
    before_world = snapshot(probe, args, "player-before")
    initial = direction_value("player.getDirection", result(rpc.call("player.getDirection", [])))
    applied = direction_value("player.setDirection",
                              result(rpc.call("player.setDirection", [1, 2, 3])))
    reread = direction_value("player.getDirection post-read",
                             result(rpc.call("player.getDirection", [])))
    after_pos = result(rpc.call("player.getPos", []))
    after_world = snapshot(probe, args, "player-after")
    if applied != reread or before_pos != after_pos:
        raise AssertionError("player direction post-read or position invariant failed")
    if before_world.get("snapshot.online_players_in_world") != "1" \
            or after_world.get("snapshot.online_players_in_world") != "1":
        raise AssertionError("dimension invariant requires exactly one online test player in target world")
    print(f"PASS player direction: get/set/get, 6 fractional digits, position/dimension unchanged; initial={initial}")

    ex, ey, ez = args.entity_point
    handle = result(rpc.call("world.spawnEntity", [ex, ey, ez, "minecraft:armor_stand"]))
    if not isinstance(handle, str) or not handle.startswith(HANDLE_PREFIX):
        raise AssertionError(f"invalid entity handle: {handle!r}")
    absolute = (args.origin[0] + ex, args.origin[1] + ey, args.origin[2] + ez)
    point = f"direction:{absolute[0]}:{absolute[1]}:{absolute[2]}"
    entity_before = snapshot(probe, args, "entity-before", point)
    direction_value("entity.getDirection", result(rpc.call("entity.getDirection", [handle])))
    entity_applied = direction_value("entity.setDirection",
                                     result(rpc.call("entity.setDirection", [handle, -1, 2, -3])))
    entity_read = direction_value("entity.getDirection post-read",
                                  result(rpc.call("entity.getDirection", [handle])))
    entity_after = snapshot(probe, args, "entity-after", point)
    invariant_keys = ("type", "x", "y", "z")
    for key in invariant_keys:
        if entity_before.get(f"snapshot.entity.direction.{key}") != \
                entity_after.get(f"snapshot.entity.direction.{key}"):
            raise AssertionError(f"entity {key} changed during setDirection")
    if entity_applied != entity_read:
        raise AssertionError("entity direction result was not stable post-read")
    print("PASS entity direction: get/set/get, 6 fractional digits, position/dimension unchanged")


def mutate_handle(rpc, probe, args, label, dimension_change):
    rx, ry, rz = args.entity_point
    offset = 3 if dimension_change else 1
    position = (rx + offset, ry, rz)
    handle = result(rpc.call("world.spawnEntity", [*position, "minecraft:armor_stand"]))
    absolute = tuple(args.origin[index] + position[index] for index in range(3))
    fields = {"world": args.dimension, "entity_x": absolute[0], "entity_y": absolute[1],
              "entity_z": absolute[2], "entity_radius": 0.75}
    if dimension_change:
        fields.update({"destination_world": args.alternate_dimension,
                       "destination_x": args.alternate_destination[0],
                       "destination_y": args.alternate_destination[1],
                       "destination_z": args.alternate_destination[2]})
        observed = probe.issue("teleport", run_id=label, **fields)
        expected = "entity_dimension_changed"
    else:
        observed = probe.issue("remove", run_id=label, **fields)
        expected = "entity_unavailable"
    if observed.get("mutation.success") != "true":
        raise AssertionError(f"probe {label} mutation failed: {observed}")
    require_reason(label, rpc.call("entity.getDirection", [handle]), expected)
    require_reason(f"{label} immediate invalidation",
                   rpc.call("entity.getDirection", [handle]), "entity_not_found")


def arm_and_strike(rpc, probe, args, run_id, relative_target, cancel,
                   block_points, entity_points):
    absolute = tuple(args.origin[index] + relative_target[index] for index in range(3))
    ready = probe.issue("arm", run_id=run_id, wait_status="ready", world=args.dimension,
                        cancel=cancel, later_ticks=args.later_ticks,
                        target_x=absolute[0], target_y=absolute[1], target_z=absolute[2],
                        block_points=block_points, entity_points=entity_points)
    require_null(f"world.strikeLightning {run_id}",
                 rpc.call("world.strikeLightning", list(relative_target)))
    observed = probe.wait_complete(ready["sequence"])
    if observed.get("event.exact_target") != "true" or observed.get("event.exact_count") != "1":
        raise AssertionError(f"{run_id}: exact target/count mismatch: {observed}")
    if observed.get("event.cause") != "CUSTOM":
        raise AssertionError(f"{run_id}: cause is not CUSTOM: {observed.get('event.cause')}")
    expected_cancelled = "true" if cancel else "false"
    if observed.get("event.cancelled.final") != expected_cancelled:
        raise AssertionError(f"{run_id}: final cancellation mismatch: {observed}")
    print(f"PASS {run_id}: exact target, one observed request event, CUSTOM, cancel={cancel}")
    observed_values = {key: value for key, value in observed.items()
                       if key.startswith(("baseline.", "tick0.", "later."))}
    print("OBSERVED " + run_id + " " + json.dumps(observed_values, sort_keys=True))
    return observed


def verify_lightning(rpc, probe, args):
    x, y, z = args.lightning_point
    set_block(rpc, (x, y - 1, z), "minecraft:netherrack")
    set_block(rpc, (x, y, z), "minecraft:air")
    transform_handle = result(rpc.call(
        "world.spawnEntity", [x + 0.5, y, z + 0.5, "minecraft:pig"]))
    result(rpc.call("world.spawnEntity", [x + 2.5, y, z + 0.5, "minecraft:cow"]))
    absolute = (args.origin[0] + x + 0.5, args.origin[1] + y, args.origin[2] + z + 0.5)
    damage = (args.origin[0] + x + 2.5, args.origin[1] + y, args.origin[2] + z + 0.5)
    blocks = ";".join(
        f"fire{index}:{args.origin[0] + x + dx}:{args.origin[1] + y}:{args.origin[2] + z + dz}"
        for index, (dx, dz) in enumerate(((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1))))
    entities = (f"transform:{absolute[0]}:{absolute[1]}:{absolute[2]};"
                f"damage:{damage[0]}:{damage[1]}:{damage[2]}")
    effects = arm_and_strike(rpc, probe, args, "full-effects",
                             absolute_relative(args, absolute), False, blocks, entities)
    transformed = effects.get("baseline.entity.transform.type") != \
        effects.get("later.entity.transform.type") or \
        effects.get("later.entity.transform.present") == "false"
    if transformed:
        require_reason("transformation handle unavailable",
                       rpc.call("entity.getDirection", [transform_handle]), "entity_unavailable")
        require_reason("transformation handle immediate invalidation",
                       rpc.call("entity.getDirection", [transform_handle]), "entity_not_found")
    else:
        print("OBSERVED transformation did not occur; handle invalidation assertion not applicable")
    time.sleep(args.rate_wait)

    rod_x = x + 8
    set_block(rpc, (rod_x, y - 1, z), "minecraft:copper_block")
    set_block(rpc, (rod_x, y, z), "minecraft:lightning_rod",
              {"facing": "up", "powered": False, "waterlogged": False})
    set_block(rpc, (rod_x + 2, y - 1, z), "minecraft:oxidized_copper")
    rod_blocks = ";".join((
        f"rod:{args.origin[0] + rod_x}:{args.origin[1] + y}:{args.origin[2] + z}",
        f"copper:{args.origin[0] + rod_x + 2}:{args.origin[1] + y - 1}:{args.origin[2] + z}",
    ))
    arm_and_strike(rpc, probe, args, "rod-copper", (rod_x, y + 1, z),
                   False, rod_blocks, "")
    time.sleep(args.rate_wait)

    cancel_x = x + 16
    set_block(rpc, (cancel_x, y - 1, z), "minecraft:netherrack")
    result(rpc.call("world.spawnEntity", [cancel_x + 0.5, y, z + 0.5, "minecraft:cow"]))
    cancel_entity = (args.origin[0] + cancel_x + 0.5,
                     args.origin[1] + y, args.origin[2] + z + 0.5)
    arm_and_strike(rpc, probe, args, "cancelled", (cancel_x + 0.5, y, z + 0.5),
                   True, "", f"cancel:{cancel_entity[0]}:{cancel_entity[1]}:{cancel_entity[2]}")


def absolute_relative(args, absolute):
    return tuple(absolute[index] - args.origin[index] for index in range(3))


def verify_particle(rpc, args):
    x, y, z = args.lightning_point
    response = rpc.call("world.spawnParticle", [x + 24.5, y + 1, z + 0.5,
                                                 0, 0, 0, "minecraft:flame", 0, 1])
    accepted = result(response)
    if accepted != 1:
        raise AssertionError(f"world.spawnParticle expected 1, got {response}")
    print("PASS world.spawnParticle regression: existing wire/default receiver path accepted count=1")


def block_positions(args):
    x, y, z = args.lightning_point
    return [(x, y - 1, z), (x, y, z), (x + 8, y - 1, z), (x + 8, y, z),
            (x + 10, y - 1, z), (x + 16, y - 1, z)]


def capture_rollback(rpc, args):
    blocks = []
    for position in block_positions(args):
        blocks.append({"position": list(position), "value": result(rpc.call("world.getBlock", position))})
    args.state_file.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.state_file.with_suffix(".tmp")
    temporary.write_text(json.dumps({"schema": 1, "blocks": blocks}, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, args.state_file)
    print(f"PASS setup: rollback manifest captured at {args.state_file}")


def cleanup(rpc, probe, args):
    if args.state_file.is_file():
        manifest = json.loads(args.state_file.read_text(encoding="utf-8"))
        for block in manifest["blocks"]:
            require_null(f"restore {block['position']}",
                         rpc.call("world.setBlock", [*block["position"], block["value"]]))
    x, y, z = args.lightning_point
    minimum = (args.origin[0] + x - 4, args.origin[1] + y - 4, args.origin[2] + z - 4)
    maximum = (args.origin[0] + x + 28, args.origin[1] + y + 8, args.origin[2] + z + 4)
    observed = probe.issue("cleanup", run_id="cleanup", world=args.dimension,
                           min_x=minimum[0], min_y=minimum[1], min_z=minimum[2],
                           max_x=maximum[0], max_y=maximum[1], max_z=maximum[2])
    print(f"PASS cleanup: entities_removed={observed.get('cleanup.entities_removed')}")


def load_token(args):
    if args.token_file:
        token = args.token_file.read_text(encoding="utf-8").strip()
        if not token.startswith(("mcrs_", "mcrl_")):
            raise ValueError("token file does not contain an McRemote token")
        return token
    if args.interactive_pair:
        return acquire_interactive_token(args)
    return None


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="McRemote protocol 23.1 b7 live-auto runner")
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--dimension", required=True)
    parser.add_argument("--alternate-dimension", required=True)
    parser.add_argument("--origin", nargs=3, type=int, required=True, metavar=("X", "Y", "Z"))
    parser.add_argument("--alternate-destination", nargs=3, type=float, required=True,
                        metavar=("X", "Y", "Z"))
    parser.add_argument("--entity-point", nargs=3, type=float, default=(0.5, 2.0, 0.5))
    parser.add_argument("--lightning-point", nargs=3, type=int, default=(32, 2, 0))
    parser.add_argument("--probe-dir", type=Path, required=True)
    parser.add_argument("--state-file", type=Path, required=True)
    parser.add_argument("--token-file", type=Path)
    parser.add_argument("--interactive-pair", action="store_true")
    parser.add_argument("--later-ticks", type=int, default=40)
    parser.add_argument("--rate-wait", type=float, default=1.25)
    parser.add_argument("--phase", choices=("all", "setup", "run", "cleanup"), default="all")
    args = parser.parse_args(argv)
    args.protocol = PROTOCOL
    if args.token_file and args.interactive_pair:
        parser.error("choose only one of --token-file and --interactive-pair")
    if args.rate_wait < 1.05:
        parser.error("--rate-wait must be at least 1.05 seconds; the runner never retries")
    return args


def main(argv=None):
    args = parse_args(argv)
    probe = ProbeController(args.probe_dir, args.timeout)
    rpc = None
    try:
        token = load_token(args)
        rpc = rpc_connect(args, token)
        if args.phase in ("all", "setup"):
            capture_rollback(rpc, args)
        if args.phase in ("all", "run"):
            verify_directions(rpc, probe, args)
            mutate_handle(rpc, probe, args, "entity-unavailable", False)
            mutate_handle(rpc, probe, args, "entity-dimension-changed", True)
            verify_lightning(rpc, probe, args)
            verify_particle(rpc, args)
        if args.phase in ("all", "cleanup"):
            cleanup(rpc, probe, args)
        print("PASS McRemote b7 live-auto runner")
        return 0
    except (AssertionError, OSError, RuntimeError, TimeoutError, ValueError, json.JSONDecodeError) as error:
        print(f"FAIL {error}", file=sys.stderr)
        print("ROLLBACK REQUIRED: run --phase cleanup, then restore the disposable world snapshot", file=sys.stderr)
        return 1
    finally:
        if rpc is not None:
            rpc.close()


if __name__ == "__main__":
    sys.exit(main())
