#!/usr/bin/env python3
"""Create a deterministic McRemote release manifest for one candidate JAR."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
from typing import Any


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
) -> dict[str, Any]:
    if not release_tag.strip():
        raise ValueError("release tag must not be empty")
    if COMMIT_PATTERN.fullmatch(source_commit) is None:
        raise ValueError("source commit must be a full 40-character Git SHA")
    if not jar_path.is_file():
        raise ValueError(f"JAR does not exist or is not a file: {jar_path}")

    return {
        "schema": SCHEMA,
        "schema_version": SCHEMA_VERSION,
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
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        manifest = create_manifest(
            release_tag=args.release_tag,
            source_commit=args.source_commit,
            jar_path=args.jar,
        )
        write_manifest(manifest, args.output)
    except (OSError, ValueError) as error:
        raise SystemExit(str(error)) from error
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
