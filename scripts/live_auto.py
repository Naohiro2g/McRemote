#!/usr/bin/env python3
"""McRemote live-auto smoke test (Python standard library only).

Runs against an isolated Paper server directly, or bootstraps one in-memory
session token with ``--interactive-pair`` on an auth-enforced server. It
exercises request/response behavior that does not require player-generated
events; event-producing Minecraft actions remain outside this script.
"""

import argparse
import json
import re
import socket
import sys
import time


from protocol_version import PROTOCOL
HANDLE = re.compile(r"^mcr_eh_[A-Za-z0-9_-]{22}$")
PAIR_CODE = re.compile(r"^[0-9]{6}$")
SESSION_TOKEN = re.compile(r"^mcrs_[A-Za-z0-9_-]{43}$")
PAIR_POLL_INTERVAL = 0.5


class Rpc:
    def __init__(self, host: str, port: int, timeout: float):
        self._socket = socket.create_connection((host, port), timeout=timeout)
        self._socket.settimeout(timeout)
        self._reader = self._socket.makefile("rb")
        self._next_id = 0

    def close(self) -> None:
        self._reader.close()
        self._socket.close()

    def call(self, method: str, params):
        self._next_id += 1
        request_id = self._next_id
        request = {
            "jsonrpc": "2.0",
            "id": request_id,
            "method": method,
            "params": params,
        }
        wire = json.dumps(request, separators=(",", ":")) + "\n"
        self._socket.sendall(wire.encode("utf-8"))
        raw = self._reader.readline()
        if not raw:
            raise RuntimeError(f"connection closed while waiting for {method}")
        response = json.loads(raw.decode("utf-8"))
        if response.get("id") != request_id:
            raise AssertionError(
                f"{method}: response id {response.get('id')} != {request_id}"
            )
        return response

    def notify(self, method: str, params) -> None:
        notification = {
            "jsonrpc": "2.0",
            "method": method,
            "params": params,
        }
        wire = json.dumps(notification, separators=(",", ":")) + "\n"
        self._socket.sendall(wire.encode("utf-8"))


def result(response: dict):
    if "error" in response:
        raise AssertionError(f"unexpected error: {response['error']}")
    return response.get("result")


def reason(response: dict) -> str | None:
    return response.get("error", {}).get("data", {}).get("reason")


def require_reason(label: str, response: dict, expected: str) -> None:
    actual = reason(response)
    if actual != expected:
        raise AssertionError(f"{label}: expected {expected}, got {response}")
    print(f"PASS {label}: {actual}")


def require_null_result(label: str, response: dict) -> None:
    expected_fields = {"jsonrpc", "id", "result"}
    if set(response) != expected_fields or response.get("jsonrpc") != "2.0":
        raise AssertionError(f"{label}: non-canonical success envelope: {response}")
    if response["result"] is not None:
        raise AssertionError(f"{label}: expected exact result:null, got {response}")


def call_once(args, method: str, params, rpc_factory=Rpc):
    """Send one pre-auth request on its own connection epoch."""
    rpc = rpc_factory(args.host, args.port, args.timeout)
    try:
        return rpc.call(method, params)
    finally:
        rpc.close()


def acquire_interactive_token(
        args,
        *,
        input_stream=None,
        output_stream=None,
        call_once_fn=None,
        monotonic=None,
        sleep=None,
) -> str:
    """Pair once without persisting or printing the issued session token."""
    input_stream = sys.stdin if input_stream is None else input_stream
    output_stream = sys.stdout if output_stream is None else output_stream
    call_once_fn = call_once if call_once_fn is None else call_once_fn
    monotonic = time.monotonic if monotonic is None else monotonic
    sleep = time.sleep if sleep is None else sleep

    if not input_stream.isatty():
        raise RuntimeError(
            "--interactive-pair requires an interactive TTY; "
            "run it from a terminal and approve the displayed Minecraft pair command"
        )

    unauthenticated = call_once_fn(
        args, "hello", {"protocol": args.protocol})
    if reason(unauthenticated) != "auth_required":
        raise AssertionError(
            "--interactive-pair requires the initial hello to return auth_required"
        )

    begun = result(call_once_fn(args, "auth.pairBegin", {
        "token_type": "session",
        "client": {"name": "live_auto.py", "version": "0", "locale": "ja"},
    }))
    pairing_id = begun.get("pairing_id") if isinstance(begun, dict) else None
    pair_code = begun.get("pair_code") if isinstance(begun, dict) else None
    expires_in = begun.get("expires_in") if isinstance(begun, dict) else None
    if not isinstance(pairing_id, str) or not pairing_id:
        raise AssertionError("auth.pairBegin returned an invalid pairing correlation")
    if not isinstance(pair_code, str) or not PAIR_CODE.fullmatch(pair_code):
        raise AssertionError("auth.pairBegin returned an invalid display code")
    if (isinstance(expires_in, bool)
            or not isinstance(expires_in, (int, float))
            or expires_in <= 0):
        raise AssertionError("auth.pairBegin returned an invalid expiry")

    print(f"/mcremote pair {pair_code[:3]}-{pair_code[3:]}",
          file=output_stream, flush=True)
    deadline = monotonic() + expires_in
    while monotonic() < deadline:
        polled = result(call_once_fn(
            args, "auth.pairPoll", {"pairing_id": pairing_id}))
        status = polled.get("status") if isinstance(polled, dict) else None
        if status == "ok":
            token = polled.get("token")
            if not isinstance(token, str) or not SESSION_TOKEN.fullmatch(token):
                raise AssertionError("auth.pairPoll returned an invalid session token")
            return token
        if status != "pending":
            raise AssertionError("auth.pairPoll returned an unexpected status")
        remaining = deadline - monotonic()
        if remaining > 0:
            sleep(min(PAIR_POLL_INTERVAL, remaining))
    raise RuntimeError("interactive pairing expired before approval")


def require_mc_version(info: dict, expected: str) -> None:
    """Stop before the test body when the server runs another Minecraft version.

    The expected version comes from the test instruction.
    knowledge DECISIONS 2026-09-28-03.
    """
    actual = info.get("mc_version")
    if actual != expected:
        raise AssertionError(
            f"hello mc_version is {actual!r}, expected {expected!r}; the test body was not run")


def connect(args, token: str | None = None, rpc_factory=Rpc) -> Rpc:
    rpc = rpc_factory(args.host, args.port, args.timeout)
    try:
        hello_params = {
            "protocol": args.protocol,
            "build": {"dimension": "overworld", "origin": [0, 0, 0]},
        }
        if token is not None:
            hello_params["auth"] = {"token": token}
        info = result(rpc.call("hello", hello_params))
        if info.get("protocol") != args.protocol:
            raise AssertionError(f"hello protocol mismatch: {info}")
        require_mc_version(info, args.expect_mc)
        expected_context = {
            "dimension": "minecraft:overworld", "origin": [0, 0, 0],
        }
        if {key: info.get(key) for key in expected_context} != expected_context:
            raise AssertionError(f"hello build context is not canonical: {info}")
        if result(rpc.call("build.setDimension", ["minecraft:overworld"])) != expected_context:
            raise AssertionError("build.setDimension did not return canonical context")
        if result(rpc.call("build.setOrigin", [0, 0, 0])) != expected_context:
            raise AssertionError("build.setOrigin did not return canonical context")
        # getHeight intentionally rejects unloaded columns; load the isolated origin first.
        result(rpc.call("world.getBlock", [0, 0, 0]))
        return rpc
    except BaseException:
        rpc.close()
        raise


def verify_b8(args, token: str | None, height: int) -> None:
    """Protocol 23.2 entity lifecycle and particle Stage 2 on a fresh connection epoch."""
    rpc = connect(args, token)
    try:
        y = height + 1
        handle = result(rpc.call("world.spawnEntity", [0.5, y, 5.5, "minecraft:cow"]))
        if not HANDLE.fullmatch(handle):
            raise AssertionError(f"invalid handle: {handle!r}")
        pose = result(rpc.call("entity.getPose", [handle]))
        if set(pose) != {"dimension", "pos", "yaw", "pitch"} or pose["dimension"] != "minecraft:overworld":
            raise AssertionError(f"entity.getPose shape: {pose}")
        print("PASS entity.getPose: dimension/pos/yaw/pitch")

        nearby = result(rpc.call("world.getNearbyEntities", [0.5, y, 5.5, 2, 4]))
        if not isinstance(nearby, list) or not any(
                item.get("handle") == handle and item.get("type") == "minecraft:cow"
                and set(item) == {"handle", "type", "pos"} for item in nearby):
            raise AssertionError(f"nearby did not reuse the spawned handle: {nearby}")
        print("PASS world.getNearbyEntities: reuses same-dimension handle, {handle,type,pos}")
        require_reason("nearby radius over cap",
                       rpc.call("world.getNearbyEntities", [0, y, 0, 65, 1]), "invalid_params")
        require_reason("nearby zero max_entities",
                       rpc.call("world.getNearbyEntities", [0, y, 0, 1, 0]), "invalid_params")

        moved = result(rpc.call("entity.setPose", [handle, "overworld", 2.5, y, 5.5, 90, 0]))
        if abs(moved["pos"][0] - 2.5) > 1e-3 or abs(moved["yaw"] - 90) > 1e-3:
            raise AssertionError(f"entity.setPose did not return the re-read pose: {moved}")
        print("PASS entity.setPose: teleport and re-read pose")

        require_null_result("entity.remove", rpc.call("entity.remove", [handle]))
        print("PASS entity.remove: result null")
        require_reason("removed handle", rpc.call("entity.getPose", [handle]), "entity_not_found")

        base = [0.5, y, 0.5, 0, 0, 0]
        for label, particle in (
                ("object default receiver", {"particle_id": "minecraft:flame"}),
                ("dust typed data", {"particle_id": "minecraft:dust",
                                     "data": {"color": [255, 80, 0], "size": 1.5}}),
                ("block typed data", {"particle_id": "minecraft:block",
                                      "data": {"block_id": "minecraft:stone", "state": {}}})):
            accepted = result(rpc.call("world.spawnParticle", base + [particle, 0, 3]))
            if accepted != 3:
                raise AssertionError(f"particle {label}: accepted {accepted!r}")
            print(f"PASS world.spawnParticle: {label}")
        require_reason("particle data on data-free particle",
                       rpc.call("world.spawnParticle", base + [
                           {"particle_id": "minecraft:flame",
                            "data": {"color": [1, 2, 3], "size": 1}}, 0, 1]),
                       "invalid_params")
        require_reason("particle unsupported typed data",
                       rpc.call("world.spawnParticle", base + [
                           {"particle_id": "minecraft:dust_color_transition",
                            "data": {"color": [1, 2, 3], "size": 1}}, 0, 1]),
                       "particle_data_unsupported")
        require_null_result("world.playSound note", rpc.call(
            "world.playSound", [0.5, y, 0.5, "minecraft:block.note_block.harp", {"note": 14}]))
        require_null_result("world.playSound pitch", rpc.call(
            "world.playSound", [0.5, y, 0.5, "minecraft:block.bell.use", {"pitch": 1.5, "volume": 0.5}]))
        print("PASS world.playSound: note and pitch accepted")
        require_null_result("world.playSound bare id", rpc.call(
            "world.playSound", [0.5, y, 0.5, "block.bell.use"]))
        bare = result(rpc.call("world.spawnParticle", base + ["flame", 0, 1]))
        if bare != 1:
            raise AssertionError(f"bare particle id not accepted: {bare!r}")
        print("PASS resource id: minecraft: filled in for sound and particle")
        require_reason("playSound unknown sound",
                       rpc.call("world.playSound", [0, y, 0, "minecraft:no.such.sound"]), "unknown_sound")
        require_reason("playSound pitch and note together",
                       rpc.call("world.playSound", [0, y, 0, "minecraft:block.bell.use",
                                                    {"pitch": 1, "note": 12}]), "invalid_params")
        require_null_result("world.playBlockSound hit", rpc.call(
            "world.playBlockSound", [0, height, 0, "hit"]))
        print("PASS world.playBlockSound: block sound group resolved")
        require_reason("playBlockSound air", rpc.call(
            "world.playBlockSound", [0, height + 5, 0, "place"]), "no_block")
        require_reason("playBlockSound unknown kind", rpc.call(
            "world.playBlockSound", [0, height, 0, "land"]), "invalid_params")
        if token is None:
            require_reason("playSound self without a bound player", rpc.call(
                "world.playSound", [0, y, 0, "minecraft:block.bell.use", {"receiver": "self"}]),
                "auth_required")
        self_spec = {"particle_id": "minecraft:flame", "receiver": "self"}
        if token is None:
            require_reason("particle self without a bound player",
                           rpc.call("world.spawnParticle", base + [self_spec, 0, 1]), "auth_required")
        else:
            result(rpc.call("world.spawnParticle", base + [self_spec, 0, 1]))
            print("PASS world.spawnParticle: self receiver accepted")
    finally:
        rpc.close()


def verify_protocol_boundary(args) -> None:
    rpc = Rpc(args.host, args.port, args.timeout)
    try:
        mismatch = rpc.call("hello", {"protocol": "21.0.0"})
        require_reason("protocol 21 rejection", mismatch, "protocol_mismatch")
        data = mismatch.get("error", {}).get("data", {})
        if data.get("server") != PROTOCOL or data.get("client_requires") != "21.0.0":
            raise AssertionError(f"protocol mismatch data is incomplete: {mismatch}")
    finally:
        rpc.close()


def verify_structured_blocks(rpc: Rpc, height: int) -> None:
    stateless = {"block_id": "minecraft:gold_block", "state": {}}
    placed = rpc.call("world.setBlock", [0, height + 1, 0, {
        "block_id": "gold_block", "state": {},
    }])
    require_null_result("world.setBlock success", placed)
    if result(rpc.call("world.getBlock", [0, height + 1, 0])) != stateless:
        raise AssertionError("stateless set/get did not round-trip")

    log_value = {"block_id": "minecraft:oak_log", "state": {"axis": "z"}}
    placed_log = rpc.call("world.setBlock", [1, height + 1, 0, {
        "block_id": "oak_log", "state": {"axis": "z"},
    }])
    require_null_result("world.setBlock partial state success", placed_log)
    if result(rpc.call("world.getBlock", [1, height + 1, 0])) != log_value:
        raise AssertionError("partial state was not completed canonically by explicit get")

    stairs_response = rpc.call("world.setBlock", [2, height + 1, 0, {
        "block_id": "oak_stairs",
        "state": {"waterlogged": True, "half": "top", "facing": "east"},
    }])
    require_null_result("world.setBlock full state success", stairs_response)
    expected_stairs = {
        "block_id": "minecraft:oak_stairs",
        "state": {
            "facing": "east", "half": "top", "shape": "straight", "waterlogged": True,
        },
    }
    stairs = result(rpc.call("world.getBlock", [2, height + 1, 0]))
    if stairs != expected_stairs:
        raise AssertionError(f"full state/default completion mismatch: {stairs}")

    fill_response = rpc.call("world.setBlocks", [3, height + 1, 0, 4, height + 1, 0, {
        "block_id": "oak_stairs", "state": {"facing": "north"},
    }])
    require_null_result("world.setBlocks success", fill_response)
    expected_fill = {
        "block_id": "minecraft:oak_stairs",
        "state": {
            "facing": "north", "half": "bottom", "shape": "straight",
            "waterlogged": False,
        },
    }
    for x in (3, 4):
        if result(rpc.call("world.getBlock", [x, height + 1, 0])) != expected_fill:
            raise AssertionError(f"setBlocks did not fill x={x}")

    validation_cases = [
        ("legacy string union rejection", "stone", "invalid_params"),
        ("missing state rejection", {"block_id": "stone"}, "invalid_params"),
        ("unknown field rejection",
         {"block_id": "stone", "state": {}, "ref": "stone"}, "invalid_params"),
        ("non-scalar state rejection",
         {"block_id": "oak_log", "state": {"axis": ["z"]}}, "invalid_params"),
        ("unknown block", {"block_id": "not_real", "state": {}}, "unknown_block"),
        ("unknown property",
         {"block_id": "stone", "state": {"axis": "z"}}, "unknown_property"),
        ("invalid property value",
         {"block_id": "oak_log", "state": {"axis": "w"}}, "invalid_property_value"),
    ]
    for label, block_spec, expected_reason in validation_cases:
        response = rpc.call("world.setBlock", [5, height + 1, 0, block_spec])
        require_reason(label, response, expected_reason)
        if "ref" in response.get("error", {}).get("data", {}):
            raise AssertionError(f"protocol 22 emitted data.ref: {response}")
    invalid_value = rpc.call("world.setBlock", [5, height + 1, 0, {
        "block_id": "oak_log", "state": {"axis": "w"},
    }])
    invalid_data = invalid_value.get("error", {}).get("data", {})
    if invalid_data.get("allowed") != ["x", "y", "z"]:
        raise AssertionError(f"invalid_property_value missing allowed values: {invalid_value}")
    result(rpc.call("world.setBlock", [3, height + 1, 1, {
        "block_id": "oak_log", "state": {"axis": "x"},
    }]))
    result(rpc.call("world.setBlock", [4, height + 1, 1, {
        "block_id": "gold_block", "state": {},
    }]))
    blocks = result(rpc.call(
        "world.getBlocks", [4, height + 1, 1, 3, height + 1, 0]))
    expected_blocks = [
        expected_fill,
        {"block_id": "minecraft:oak_log", "state": {"axis": "x"}},
        expected_fill,
        {"block_id": "minecraft:gold_block", "state": {}},
    ]
    if blocks != expected_blocks:
        raise AssertionError(f"getBlocks order/full state mismatch: {blocks}")
    require_reason(
        "world.getBlocks axis limit",
        rpc.call("world.getBlocks", [0, height + 1, 0, 10, height + 1, 0]),
        "work_limit_exceeded",
    )
    require_reason(
        "world.getBlocks integer validation",
        rpc.call("world.getBlocks", [0, height + 1, 0, 1.5, height + 1, 0]),
        "invalid_params",
    )
    require_reason(
        "legacy world.getBlockWithData removal",
        rpc.call("world.getBlockWithData", [0, height + 1, 0]),
        "method_not_found",
    )
    print("PASS structured BlockSpec/BlockValue: strict shape, defaults, set/get/getBlocks/setBlocks")


def verify_flush_and_notifications(rpc: Rpc, height: int, queue_capacity: int) -> None:
    require_null_result("connection.flush", rpc.call("connection.flush", []))
    require_reason(
        "connection.flush exact params",
        rpc.call("connection.flush", {}),
        "invalid_params",
    )

    coordinate = [7, height + 1, 0]
    rpc.notify("world.setBlock", coordinate + [
        {"block_id": "gold_block", "state": {}},
    ])
    rpc.notify("world.setBlock", coordinate + [
        {"block_id": "oak_log", "state": {"axis": "x"}},
    ])
    rpc.notify("world.setBlock", coordinate + [
        {"block_id": "diamond_block", "state": {}},
    ])
    # Any synthetic notification response would be read here and fail the request-id check.
    require_null_result("FAST sequence flush", rpc.call("connection.flush", []))
    expected_final = {"block_id": "minecraft:diamond_block", "state": {}}
    if result(rpc.call("world.getBlock", coordinate)) != expected_final:
        raise AssertionError("FAST notifications were dropped, reordered, or overtaken by flush")

    baseline = {"block_id": "minecraft:emerald_block", "state": {}}
    require_null_result(
        "notification baseline set",
        rpc.call("world.setBlock", coordinate + [baseline]),
    )
    rpc.notify("world.setBlock", coordinate + [
        {"block_id": "not_real", "state": {}},
    ])
    # The invalid notification is terminal and silent; flush does not aggregate its error.
    require_null_result("invalid notification flush", rpc.call("connection.flush", []))
    if result(rpc.call("world.getBlock", coordinate)) != baseline:
        raise AssertionError("invalid notification changed the world")

    rejected_coordinate = [8, height + 1, 0]
    require_null_result(
        "work-limit notification baseline set",
        rpc.call("world.setBlock", rejected_coordinate + [baseline]),
    )
    rpc.notify("world.setBlocks", [
        8, height + 1, 0, 24, height + 17, 16,
        {"block_id": "gold_block", "state": {}},
    ])
    require_null_result("work-limit notification flush", rpc.call("connection.flush", []))
    if result(rpc.call("world.getBlock", rejected_coordinate)) != baseline:
        raise AssertionError("work-limit notification was not terminal before flush")

    burst_count = queue_capacity + 16
    alternatives = (
        {"block_id": "gold_block", "state": {}},
        {"block_id": "iron_block", "state": {}},
    )
    for index in range(burst_count):
        rpc.notify("world.setBlock", coordinate + [alternatives[index % 2]])
    rpc.notify("world.setBlock", coordinate + [
        {"block_id": "lapis_block", "state": {}},
    ])
    require_null_result("queue-capacity burst flush", rpc.call("connection.flush", []))
    expected_burst_final = {"block_id": "minecraft:lapis_block", "state": {}}
    if result(rpc.call("world.getBlock", coordinate)) != expected_burst_final:
        raise AssertionError("queue-capacity burst silently dropped or reordered a notification")
    print(
        "PASS connection FIFO/flush: no synthetic response, invalid notification terminal, "
        f"{burst_count + 1} notification capacity burst preserved"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="McRemote live-auto")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--protocol", default=PROTOCOL,
                        help="client protocol for hello (default: %(default)s)")
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--handle-capacity", type=int, default=8)
    parser.add_argument("--particle-limit", type=int, default=100)
    parser.add_argument("--queue-capacity", type=int, default=1024)
    parser.add_argument(
        "--expect-mc",
        required=True,
        help="Minecraft version the test instruction names; the run fails before the test body "
             "if hello reports another mc_version",
    )
    parser.add_argument(
        "--interactive-pair",
        action="store_true",
        help="pair once in Minecraft and keep the session token in memory only",
    )
    args = parser.parse_args()

    primary = secondary = None
    try:
        verify_protocol_boundary(args)
        token = acquire_interactive_token(args) if args.interactive_pair else None
        primary = connect(args, token)
        secondary = connect(args, token)
        print("PASS hello/build state: two independent connection epochs")
        require_reason(
            "legacy build.setWorld removal",
            primary.call("build.setWorld", ["overworld"]),
            "method_not_found",
        )
        require_reason(
            "DimensionRef case rejection",
            primary.call("build.setDimension", ["Overworld"]),
            "invalid_params",
        )
        require_reason(
            "world is not an overworld alias",
            primary.call("build.setDimension", ["world"]),
            "unknown_dimension",
        )
        print("PASS DimensionKey aliases removed and invalid refs rejected")

        height = result(primary.call("world.getHeight", [0, 0]))
        if not isinstance(height, int):
            raise AssertionError(f"height must be integer: {height!r}")
        print(f"PASS world.getHeight: relative height={height}")
        verify_structured_blocks(primary, height)
        verify_flush_and_notifications(primary, height, args.queue_capacity)
        require_reason(
            "height fractional integer rejection",
            primary.call("world.getHeight", [0.5, 0]),
            "invalid_params",
        )
        require_reason(
            "height JSON string rejection",
            primary.call("world.getHeight", ["0", 0]),
            "invalid_params",
        )
        require_reason(
            "height empty lower layer",
            primary.call("world.getHeight", [0, 0, -1000]),
            "height_not_found",
        )

        empty = result(primary.call("events.poll", [0]))
        required = {
            "events",
            "through_sequence",
            "latest_sequence",
            "filtered_out",
            "overflow_dropped_total",
            "capacity_dropped_total",
            "explicitly_discarded_total",
        }
        if set(empty) != required or empty["events"] != []:
            raise AssertionError(f"unexpected empty poll shape: {empty}")
        if any(empty[key] != 0 for key in required - {"events"}):
            raise AssertionError(f"new epoch counters must start at zero: {empty}")
        require_reason(
            "events future cursor",
            primary.call("events.poll", [1, {"max_events": 8}]),
            "invalid_params",
        )
        require_reason(
            "events legacy flat limit",
            primary.call("events.poll", [0, 8]),
            "invalid_params",
        )
        require_reason(
            "events unknown option",
            primary.call("events.poll", [0, {"limit": 8}]),
            "invalid_params",
        )
        if result(secondary.call("events.poll", [0, {"max_events": 8}])) != empty:
            raise AssertionError("new connection epoch does not have independent counters")
        print("PASS events.poll: default/options shape, legacy rejection, epoch independence")

        require_reason(
            "events.clear is b6-only",
            primary.call("events.clear", []),
            "method_not_found",
        )

        particle_params = [0.5, height + 1, 0.5, 0, 0, 0, "minecraft:flame", 0, 1]
        accepted = result(primary.call("world.spawnParticle", particle_params))
        if accepted != 1:
            raise AssertionError(f"particle accepted count mismatch: {accepted!r}")
        print("PASS world.spawnParticle: canonical no-data particle")
        require_reason(
            "particle unknown ID",
            primary.call("world.spawnParticle", particle_params[:6]
                         + ["minecraft:not_real", 0, 1]),
            "unknown_particle",
        )
        require_reason(
            "particle typed-data rejection",
            primary.call("world.spawnParticle", particle_params[:6]
                         + ["minecraft:dust", 0, 1]),
            "particle_data_required",
        )
        require_reason(
            "particle negative count",
            primary.call("world.spawnParticle", particle_params[:8] + [-1]),
            "invalid_params",
        )
        require_reason(
            "particle work limit",
            primary.call("world.spawnParticle", particle_params[:8]
                         + [args.particle_limit + 1]),
            "work_limit_exceeded",
        )
        far_particle = [800, height + 1, 800, 0, 0, 0, "minecraft:flame", 0, 1]
        accepted = result(primary.call("world.spawnParticle", far_particle))
        if accepted != 1:
            raise AssertionError(
                f"particle unloaded chunk accepted count mismatch: {accepted!r}")
        print("PASS world.spawnParticle: unloaded chunk loaded before spawn")

        require_reason(
            "entity unknown ID",
            primary.call("world.spawnEntity", [0.5, height + 1, 0.5, "minecraft:not_real"]),
            "unknown_entity",
        )
        require_reason(
            "entity player rejection",
            primary.call("world.spawnEntity", [0.5, height + 1, 0.5, "minecraft:player"]),
            "entity_not_spawnable",
        )
        handles = []
        for index in range(args.handle_capacity):
            handle = result(primary.call(
                "world.spawnEntity",
                [0.5 + index, height + 1, 0.5, "minecraft:cow"],
            ))
            if not isinstance(handle, str) or not HANDLE.fullmatch(handle):
                raise AssertionError(f"invalid handle: {handle!r}")
            handles.append(handle)
        if len(set(handles)) != args.handle_capacity:
            raise AssertionError("spawned entities did not receive unique handles")
        require_reason(
            "entity handle capacity",
            primary.call("world.spawnEntity", [20.5, height + 1, 0.5, "minecraft:cow"]),
            "entity_capacity_exhausted",
        )
        secondary_handle = result(secondary.call(
            "world.spawnEntity", [30.5, height + 1, 0.5, "minecraft:cow"]
        ))
        if not HANDLE.fullmatch(secondary_handle):
            raise AssertionError(f"second epoch handle invalid: {secondary_handle!r}")
        print("PASS world.spawnEntity: opaque handles, capacity, epoch independence")
        verify_b8(args, token, height)

        require_reason(
            "block coordinate fraction rejection",
            primary.call("world.setBlock", [
                0.5, height + 1, 0, {"block_id": "stone", "state": {}}
            ]),
            "invalid_params",
        )

        print("PASS: McRemote live-auto (non-human subset)")
        return 0
    except (AssertionError, OSError, RuntimeError, json.JSONDecodeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    finally:
        if primary is not None:
            primary.close()
        if secondary is not None:
            secondary.close()


if __name__ == "__main__":
    sys.exit(main())
