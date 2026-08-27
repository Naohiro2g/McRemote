#!/usr/bin/env python3
"""McRemote live-human event/pose verification runner.

This script intentionally pauses at the human boundary: a player must join the
target Paper server, approve pairing, then perform three Minecraft actions.
Tokens are kept in memory and never printed or written.
"""

import argparse
import json
import socket
import sys
import time


PROTOCOL = "23.0.0"
CHAT_MARKER = "MCR_B5_CHAT"
EVENT_TYPES = {"pickaxe_poke", "chat_posted", "projectile_hit"}


class Rpc:
    def __init__(self, host: str, port: int, timeout: float):
        self.socket = socket.create_connection((host, port), timeout=timeout)
        self.socket.settimeout(timeout)
        self.reader = self.socket.makefile("rb")
        self.next_id = 0

    def close(self):
        self.reader.close()
        self.socket.close()

    def call(self, method: str, params):
        self.next_id += 1
        request = {
            "jsonrpc": "2.0",
            "id": self.next_id,
            "method": method,
            "params": params,
        }
        self.socket.sendall(
            (json.dumps(request, separators=(",", ":")) + "\n").encode("utf-8")
        )
        raw = self.reader.readline()
        if not raw:
            raise RuntimeError(f"connection closed during {method}")
        response = json.loads(raw.decode("utf-8"))
        if response.get("id") != self.next_id:
            raise AssertionError(f"response id mismatch during {method}: {response}")
        return response


def successful(response: dict):
    if "error" in response:
        raise AssertionError(response["error"])
    return response["result"]


def pair(primary: Rpc, poll_interval: float) -> str:
    begun = successful(primary.call("auth.pairBegin", {
        "token_type": "session",
        "client": {"name": "live_human.py", "version": "0", "locale": "ja"},
    }))
    code = begun["pair_code"]
    grouped = f"{code[:3]}-{code[3:]}"
    print("Minecraftチャットで次を実行してください:")
    print(f"  /mcremote pair {grouped}")
    deadline = time.monotonic() + begun["expires_in"]
    while time.monotonic() < deadline:
        polled = successful(primary.call(
            "auth.pairPoll", {"pairing_id": begun["pairing_id"]}
        ))
        if polled.get("status") == "ok":
            print("PASS pairing approved")
            return polled["token"]
        if polled.get("status") != "pending":
            raise AssertionError(f"unexpected pair status: {polled}")
        time.sleep(poll_interval)
    raise AssertionError("pairing timed out")


def hello(rpc: Rpc, token: str) -> dict:
    info = successful(rpc.call("hello", {
        "protocol": PROTOCOL,
        "auth": {"token": token},
        "build": {"dimension": "overworld", "origin": [200, 0, 200]},
    }))
    if not info.get("player"):
        raise AssertionError(f"hello did not bind player: {info}")
    if info.get("dimension") != "minecraft:overworld":
        raise AssertionError(f"hello dimension is not canonical: {info}")
    return info


def canonical_pose(pose: dict) -> bool:
    try:
        if (not isinstance(pose["dimension"], str) or ":" not in pose["dimension"]
                or "world" in pose):
            return False
        if len(pose["pos"]) != 3:
            return False
        position_ok = all(decimal_places(value) <= 3 for value in pose["pos"])
        angle_ok = decimal_places(pose["yaw"]) <= 2 and decimal_places(pose["pitch"]) <= 2
        yaw_ok = -180 <= float(pose["yaw"]) < 180
        pitch_ok = -90 <= float(pose["pitch"]) <= 90
        return position_ok and angle_ok and yaw_ok and pitch_ok
    except (KeyError, TypeError, ValueError):
        return False


def decimal_places(value) -> int:
    text = format(value, "f") if isinstance(value, float) else str(value)
    return len(text.rstrip("0").split(".", 1)[1]) if "." in text.rstrip("0") else 0


def collect_events(rpc: Rpc, timeout: float, poll_interval: float) -> list[dict]:
    cursor = 0
    collected = []
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        batch = successful(rpc.call("events.poll", [cursor, {"max_events": 8}]))
        collected.extend(batch["events"])
        cursor = batch["through_sequence"]
        if EVENT_TYPES.issubset({event.get("type") for event in collected}):
            return collected
        time.sleep(poll_interval)
    raise AssertionError(f"timed out waiting for event types; got {collected}")


def validate_events(events: list[dict]) -> None:
    sequences = [event.get("sequence") for event in events]
    if sequences != sorted(sequences) or len(sequences) != len(set(sequences)):
        raise AssertionError(f"events are not FIFO with unique sequence: {sequences}")
    pokes = [event for event in events if event.get("type") == "pickaxe_poke"]
    if len(pokes) != 1:
        raise AssertionError(f"one poke must become one event, got {len(pokes)}")
    validate_block_value(pokes[0].get("block"), "pickaxe_poke.block")
    if not isinstance(pokes[0].get("item"), str) or ":" not in pokes[0]["item"]:
        raise AssertionError(f"pickaxe_poke.item is not a fully qualified item id: {pokes[0]!r}")
    chats = [event for event in events if event.get("type") == "chat_posted"]
    if not any(event.get("message") == CHAT_MARKER for event in chats):
        raise AssertionError(f"original chat marker missing: {chats}")
    projectiles = [event for event in events if event.get("type") == "projectile_hit"]
    if not any(event.get("target", {}).get("kind") == "block" for event in projectiles):
        raise AssertionError(f"block projectile target missing: {projectiles}")
    for event in projectiles:
        target = event.get("target", {})
        if target.get("kind") == "block":
            validate_block_value(target.get("block"), "projectile_hit.target.block")
    for event in events:
        dimension = event.get("dimension")
        if (not isinstance(dimension, str) or ":" not in dimension
                or "world" in event or len(event.get("origin", [])) != 3):
            raise AssertionError(f"event lacks canonical dimension/origin: {event}")


def validate_block_value(value, label: str) -> None:
    if not isinstance(value, dict) or set(value) != {"block_id", "state"}:
        raise AssertionError(f"{label} is not an exact BlockValue: {value}")
    if not isinstance(value["block_id"], str) or ":" not in value["block_id"]:
        raise AssertionError(f"{label}.block_id is not fully qualified: {value}")
    if not isinstance(value["state"], dict):
        raise AssertionError(f"{label}.state is not an object: {value}")


def main() -> int:
    parser = argparse.ArgumentParser(description="McRemote live-human runner")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("--poll-interval", type=float, default=0.5)
    args = parser.parse_args()

    primary = secondary = None
    try:
        primary = Rpc(args.host, args.port, args.timeout)
        token = pair(primary, args.poll_interval)
        primary_info = hello(primary, token)
        secondary = Rpc(args.host, args.port, args.timeout)
        secondary_info = hello(secondary, token)
        if primary_info["player"] != secondary_info["player"]:
            raise AssertionError("connection epochs did not bind the same player")
        print("PASS two active epochs bound to the same paired player")

        pose = successful(primary.call("player.getPose", []))
        if not canonical_pose(pose):
            raise AssertionError(f"player pose is not canonical: {pose}")
        print(f"PASS canonical player pose: {pose}")

        print()
        print("Minecraftで次を各1回、順番に実行してください:")
        print("  1. ツルハシを持ってブロックを右クリック（poke）")
        print(f"  2. チャットへ {CHAT_MARKER} と投稿")
        print("  3. 矢などのprojectileをブロックへ当てる")
        input("完了したら Enter: ")

        primary_events = collect_events(primary, args.timeout, args.poll_interval)
        secondary_events = collect_events(secondary, args.timeout, args.poll_interval)
        validate_events(primary_events)
        validate_events(secondary_events)
        if primary_events != secondary_events:
            raise AssertionError(
                "same player epochs received different event DTOs\n"
                f"primary={primary_events}\nsecondary={secondary_events}"
            )
        print("PASS FIFO event projection, hand normalization, and epoch replication")

        replay = successful(primary.call(
            "events.poll", [0, {"max_events": 8}]
        ))["events"]
        if replay != primary_events:
            raise AssertionError("same cursor did not replay the same immutable DTOs")
        print("PASS non-destructive replay with the same cursor")

        successful(primary.call("build.setOrigin", [10, 0, 10]))
        replay_after_origin_change = successful(primary.call(
            "events.poll", [0, {"max_events": 8}]
        ))["events"]
        if replay_after_origin_change != primary_events:
            raise AssertionError("captured DTO changed after build origin mutation")
        print("PASS event dimension/origin captured at listener time")

        print("PASS: McRemote live-human event/pose subset")
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
