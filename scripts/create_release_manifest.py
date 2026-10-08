#!/usr/bin/env python3
"""Create a deterministic McRemote release manifest for one candidate JAR."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from typing import Any

from release_manifest_v2 import (DECLARATION_PATH, load_json,
                                 read_verification_record, validate_candidate_files,
                                 validate_locked_schema, validate_v2)

SCHEMA = "mc-remote.release-manifest"
SCHEMA_VERSION = 1
COMMIT_PATTERN = re.compile(r"[0-9a-fA-F]{40}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def create_manifest(
    release_tag: str,
    source_commit: str,
    jar_path: Path,
    *,
    schema_version: int = 1,
    declaration_path: Path | None = None,
    verification_records: list[Path] | None = None,
) -> dict[str, Any]:
    if not release_tag.strip():
        raise ValueError("release tag must not be empty")
    if COMMIT_PATTERN.fullmatch(source_commit) is None:
        raise ValueError("source commit must be a full 40-character Git SHA")
    if not jar_path.is_file():
        raise ValueError(f"JAR does not exist or is not a file: {jar_path}")

    if type(schema_version) is not int or schema_version not in (1, 2):
        raise ValueError("unknown manifest schema version")
    if schema_version == 1 and (declaration_path is not None or verification_records is not None):
        raise ValueError("v1 cannot contain v2 compatibility fields")

    manifest = {
        "schema": SCHEMA,
        "schema_version": schema_version,
        "release_tag": release_tag,
        "source_commit": source_commit.lower(),
        "artifacts": [
            {
                "role": "jar",
                "kind": "https-file",
                "file": jar_path.name,
                "sha256": sha256_file(jar_path),
            }
        ],
    }
    if schema_version == 1:
        return manifest
    if declaration_path is None or not verification_records:
        raise ValueError("v2 requires a target declaration and per-version verification records")
    manifest["artifacts"][0]["bytes"] = jar_path.stat().st_size
    declaration_hash = sha256_file(declaration_path)
    declaration = load_json(declaration_path)
    if not isinstance(declaration, dict):
        raise ValueError("target declaration must be an object")
    verifications = []
    for path in verification_records:
        measured = read_verification_record(path, source_commit.lower(), declaration_hash)
        verifications.append({**measured, "record": {"file": path.name, "sha256": sha256_file(path)}})
    manifest["minecraft_compatibility"] = {
        "declaration": {"path": DECLARATION_PATH, "sha256": declaration_hash,
                        "minecraft_versions": declaration.get("minecraft_versions")},
        "verifications": verifications,
    }
    validate_v2(manifest)
    order = {version: index for index, version in enumerate(declaration["minecraft_versions"])}
    verifications.sort(key=lambda check: order[check["minecraft_version"]])
    if any(path.parent != verification_records[0].parent for path in verification_records):
        raise ValueError("verification records must be staged in the same Release asset directory")
    validate_candidate_files(manifest, jar_path, declaration_path, verification_records[0].parent)
    return manifest


def write_manifest(manifest: dict[str, Any], output_path: Path) -> None:
    output_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--schema-version", type=int, choices=(1, 2), default=1)
    parser.add_argument("--declaration", type=Path)
    parser.add_argument("--verification-record", type=Path, action="append")
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--schema-lock", type=Path, default=Path(__file__).resolve().parents[1] / "release/release-manifest-lock.json")
    parser.add_argument("--preflight", action="store_true",
                        help="local candidate only: do not claim fixed shared-schema validation")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        manifest = create_manifest(
            release_tag=args.release_tag,
            source_commit=args.source_commit,
            jar_path=args.jar,
            schema_version=args.schema_version,
            declaration_path=args.declaration,
            verification_records=args.verification_record,
        )
        if args.schema_version == 2:
            result = subprocess.run(["git", "show", f"{manifest['source_commit']}:{DECLARATION_PATH}"],
                                    cwd=args.repo_root, capture_output=True, check=False)
            if result.returncode != 0 or result.stdout != args.declaration.read_bytes():
                raise ValueError("declaration differs from producer source_commit")
            if not args.preflight:
                validate_locked_schema(manifest, args.schema_lock)
        write_manifest(manifest, args.output)
    except (OSError, ValueError) as error:
        raise SystemExit(str(error)) from error
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
