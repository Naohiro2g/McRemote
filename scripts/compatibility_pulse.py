#!/usr/bin/env python3
"""同じcandidate JARを隔離ローカルPaperで確認し、sanitized素材を返す。"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import time

from live_auto import Rpc, PROTOCOL, result, require_null_result, require_reason, verify_structured_blocks
from sign_live_auto import verify_sign, verify_get_sign_and_style, verify_update_sign_line


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sanitize(value):
    encoded = json.dumps(value, ensure_ascii=False)
    encoded = re.sub(r"mcr[ls]_[A-Za-z0-9_-]+", "[REDACTED_TOKEN]", encoded)
    encoded = re.sub(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", "[REDACTED_UUID]", encoded, flags=re.I)
    return json.loads(encoded)


class RecordedRpc(Rpc):
    def __init__(self, port: int, transcript: list):
        super().__init__("127.0.0.1", port, 30)
        self.transcript = transcript

    def call(self, method, params):
        for attempt in range(50):
            response = self._record_call(method, params)
            # Only this contract reason permits automatic retry before side effects.
            if response.get("error", {}).get("data", {}).get("reason") != "backpressure":
                return response
            time.sleep(0.06)
        raise AssertionError(f"temporary pressure did not clear for {method}")

    def _record_call(self, method, params):
        response = super().call(method, params)
        recorded = response
        if method == "catalog.get" and "result" in response:
            data = response["result"]
            recorded = {"id": response.get("id"), "result_summary": {
                "keys": sorted(data), "sha256": hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest()}}
        self.transcript.append(sanitize({"method": method, "params": params, "response": recorded}))
        return response

    def notify(self, method, params):
        super().notify(method, params)
        self.transcript.append(sanitize({"method": method, "params": params, "notification": True}))


def run(args) -> dict:
    declaration = json.loads(args.targets.read_text())
    targets = declaration["minecraft_versions"]
    assert args.expect_mc in targets
    before = digest(args.jar)
    token = args.token_file.read_text().strip()
    transcript, passed = [], []
    rpc = RecordedRpc(args.port, transcript)
    try:
        hello = result(rpc.call("hello", {"protocol": PROTOCOL, "auth": {"token": token},
                          "build": {"dimension": "overworld", "origin": [0, 0, 0]}}))
        assert hello["mc_version"] == args.expect_mc
        assert hello["supported_mc_versions"] == targets
        assert hello["dimension"] == "minecraft:overworld"
        assert hello["permissions"]["online"] and hello["permissions"]["offline"]
        passed.append("authenticated_hello_and_bundled_targets")
        catalog = result(rpc.call("catalog.get", []))
        assert catalog and hello["catalogHash"]
        passed.append("resource_catalog")
        context = result(rpc.call("build.setDimension", ["minecraft:overworld"]))
        assert context["dimension"] == "minecraft:overworld"
        result(rpc.call("world.getBlock", [0, 0, 0]))
        height = result(rpc.call("world.getHeight", [0, 0]))
        verify_structured_blocks(rpc, height)
        passed.append("structured_block_read_write")
        if not args.restart_only:
            y = height + 4
            stone = {"block_id": "minecraft:stone", "state": {}}
            require_null_result("32768 setBlocks", rpc.call("world.setBlocks", [64, y, 64, 95, y + 31, 95, stone]))
            assert result(rpc.call("world.getBlock", [95, y + 31, 95])) == stone
            rpc.notify("world.setBlocks", [64, y, 64, 95, y + 31, 95, {"block_id": "gold_block", "state": {}}])
            require_null_result("bulk notification barrier", rpc.call("connection.flush", []))
            assert result(rpc.call("world.getBlock", [95, y + 31, 95]))["block_id"] == "minecraft:gold_block"
            require_reason("default 32769 rejection", rpc.call("world.setBlocks", [0, y, 0, 330, y + 10, 8, stone]), "build_denied")
            passed.append("bulk_32768_scheduler_and_notification_flush")
            verify_sign(rpc, height)
            verify_get_sign_and_style(rpc, height)
            verify_update_sign_line(rpc, height)
            passed.append("sign_read_write_style")
            handle = result(rpc.call("world.spawnEntity", [4.5, height + 1, 4.5, "minecraft:pig"]))
            assert isinstance(handle, str) and handle.startswith("mcr_eh_")
            assert result(rpc.call("entity.getPose", [handle]))["dimension"] == "minecraft:overworld"
            require_null_result("entity remove", rpc.call("entity.remove", [handle]))
            passed.append("entity_handle_pose_remove")
            events = result(rpc.call("events.poll", [0]))
            assert isinstance(events["events"], list)
            passed.append("event_poll_surface")
            assert result(rpc.call("world.spawnParticle", [0, height + 2, 0, 0, 0, 0, "minecraft:flame", 0, 1])) == 1
            require_null_result("chat.post", rpc.call("chat.post", ["b10 local compatibility pulse"]))
            passed.append("particle_and_chat")
        else:
            passed.append("ordinary_restart_authenticated_read_write")
        require_null_result("final flush", rpc.call("connection.flush", []))
        assert digest(args.jar) == before
        return {"schema": "mc-remote.local-compatibility-pulse", "schema_version": 1,
                "minecraft_version": args.expect_mc, "jar_sha256": before,
                "declaration_sha256": digest(args.targets), "declared_versions": targets,
                "restart_only": args.restart_only, "status": "PASS", "assertions": passed,
                "non_claims": ["live player event callbacks", "classroom load/MSPT", "public world migration"],
                "transcript": transcript}
    finally:
        rpc.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--expect-mc", required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--targets", type=Path, default=Path(__file__).resolve().parents[1] / "release/minecraft-targets.json")
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--restart-only", action="store_true")
    args = parser.parse_args()
    report = run(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"PASS Minecraft {args.expect_mc}: {len(report['assertions'])} groups, JAR {report['jar_sha256']}")


if __name__ == "__main__":
    main()
