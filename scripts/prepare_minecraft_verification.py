#!/usr/bin/env python3
"""Combine sanitized pulse/restart/lifecycle observations into one Release record.

This preserves measured source and runtime identities; it does not re-label a PASS
for a different source commit or JAR, or make the coordinator's release judgment.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from compatibility_pulse import sanitize
from release_manifest_v2 import (hex_string, integer, load_json, string,
                                 verification_filename)


PULSE_IDENTITY = (
    "source_commit", "minecraft_version", "paper_build", "paper_server_sha256",
    "java_version", "jar_sha256", "declaration_sha256", "declared_versions",
)


def prepare_record(pulse: dict, restart: dict, lifecycle: dict) -> dict:
    for observation, restarted in ((pulse, False), (restart, True)):
        if observation.get("schema") != "mc-remote.local-compatibility-pulse" or type(observation.get("schema_version")) is not int or observation["schema_version"] != 1:
            raise ValueError("expected a local compatibility pulse")
        if observation.get("status") != "PASS" or observation.get("restart_only") is not restarted:
            raise ValueError("full pulse and ordinary-restart pulse must both be PASS")
        if not all(key in observation for key in PULSE_IDENTITY):
            raise ValueError("pulse must include measured candidate/server/runtime identities")
    if any(pulse[key] != restart[key] for key in PULSE_IDENTITY):
        raise ValueError("pulse and restart measured identities differ")
    for key in ("source_commit", "minecraft_version", "jar_sha256"):
        if lifecycle.get(key) != pulse[key]:
            raise ValueError("lifecycle measured identity differs")
    observations = lifecycle.get("observations")
    if not isinstance(observations, list) or len(observations) != 2:
        raise ValueError("two ordinary lifecycle observations are required")
    if any(not isinstance(item, dict) or any(item.get(key) is not True for key in
           ("enabled", "disabled", "ready", "auth_enforcement_on")) for item in observations):
        raise ValueError("ordinary enable/disable and authenticated readiness must be observed")
    hex_string(pulse["source_commit"], 40, "source_commit")
    for key in ("paper_server_sha256", "jar_sha256", "declaration_sha256"):
        hex_string(pulse[key], 64, key)
    integer(pulse["paper_build"], 1, "paper_build")
    string(pulse["java_version"], "java_version")
    verification_filename(pulse["minecraft_version"])
    return {
        "schema": "mc-remote.minecraft-verification", "schema_version": 1,
        "source_commit": pulse["source_commit"],
        "declaration_sha256": pulse["declaration_sha256"],
        "minecraft_version": pulse["minecraft_version"],
        "paper_build": pulse["paper_build"],
        "server_sha256": pulse["paper_server_sha256"],
        "java_version": pulse["java_version"],
        "jar_sha256": pulse["jar_sha256"], "result": "PASS",
        "observations": sanitize({"pulse": pulse, "ordinary_restart": restart, "lifecycle": lifecycle}),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pulse", type=Path, required=True)
    parser.add_argument("--restart-pulse", type=Path, required=True)
    parser.add_argument("--lifecycle", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        record = prepare_record(load_json(args.pulse), load_json(args.restart_pulse), load_json(args.lifecycle))
        args.output_dir.mkdir(parents=True, exist_ok=True)
        output = args.output_dir / verification_filename(record["minecraft_version"])
        output.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(output)
    except (OSError, ValueError, TypeError) as error:
        raise SystemExit(str(error)) from error
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
