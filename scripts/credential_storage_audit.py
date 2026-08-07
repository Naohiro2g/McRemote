#!/usr/bin/env python3
"""Audit server credential storage and latest.log without printing a raw token."""

import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import sys

from pair_test import load_token_file


BEARER_PATTERN = re.compile(rb"mcr[sl]_[A-Za-z0-9_-]{16,}")


def regular_files(root: Path) -> list[Path]:
    if not root.is_dir():
        raise ValueError("plugin data directory does not exist")
    files = []
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            continue
        if path.is_file():
            files.append(path)
    return files


def scan_bytes(paths: list[Path], token: bytes, body: bytes) -> tuple[int, int, int]:
    exact_matches = 0
    body_matches = 0
    bearer_patterns = 0
    for path in paths:
        data = path.read_bytes()
        exact_matches += data.count(token)
        body_matches += data.count(body)
        bearer_patterns += len(BEARER_PATTERN.findall(data))
    return exact_matches, body_matches, bearer_patterns


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Verify server storage/logs contain hashes, not a raw credential"
    )
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--plugin-data", required=True)
    parser.add_argument("--server-log", required=True)
    parser.add_argument("--phase", required=True, choices=["pre-restart", "post-restart"])
    parser.add_argument("--snapshot", default="credential-store/snapshot.json")
    parser.add_argument("--authority", default="credential-revocations")
    args = parser.parse_args()

    try:
        token, token_type, _ = load_token_file(args.token_file)
        if token_type != "long_lived":
            raise AssertionError("storage audit requires a long-lived credential")
        token_bytes = token.encode("ascii")
        body_bytes = token_bytes[5:]
        digest = hashlib.sha256(token_bytes).digest()
        token_hash = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")

        plugin_data = Path(args.plugin_data).resolve()
        plugin_files = regular_files(plugin_data)
        exact, body, bearer = scan_bytes(plugin_files, token_bytes, body_bytes)
        if exact or body or bearer:
            raise AssertionError(
                "raw bearer material found in plugin data: "
                f"exact={exact} body={body} patterns={bearer}"
            )

        server_log = Path(args.server_log).resolve()
        if not server_log.is_file() or server_log.is_symlink():
            raise ValueError("server log must be a regular file")
        log_exact, log_body, log_bearer = scan_bytes(
            [server_log], token_bytes, body_bytes
        )
        if log_exact or log_body or log_bearer:
            raise AssertionError(
                "raw bearer material found in normal log: "
                f"exact={log_exact} body={log_body} patterns={log_bearer}"
            )

        snapshot_path = plugin_data / args.snapshot
        snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
        records = snapshot.get("records")
        if not isinstance(records, list):
            raise AssertionError("credential snapshot records must be a list")
        matching_records = [record for record in records if record.get("token_hash") == token_hash]
        if len(matching_records) != 1:
            raise AssertionError(
                f"expected one hash-only snapshot record, got {len(matching_records)}"
            )
        if matching_records[0].get("revoked_at") is None:
            raise AssertionError("snapshot record must project revoked_at after revoke")

        authority_root = plugin_data / args.authority
        authority_hash_records = 0
        for path in regular_files(authority_root):
            try:
                document = json.loads(path.read_text(encoding="utf-8"))
            except (UnicodeError, json.JSONDecodeError):
                continue
            if document.get("token_hash") == token_hash:
                authority_hash_records += 1
        if authority_hash_records != 1:
            raise AssertionError(
                f"expected one authority tombstone hash, got {authority_hash_records}"
            )

        print(
            f"[storage.audit] phase={args.phase} files={len(plugin_files)} "
            "raw_exact=0 raw_body=0 bearer_patterns=0"
        )
        print(
            f"[log.audit] phase={args.phase} files=1 "
            "raw_exact=0 raw_body=0 bearer_patterns=0"
        )
        print(
            f"[hash-only] phase={args.phase} snapshot_records=1 "
            "authority_tombstones=1 revoked_projection=true"
        )
        print("PASS: server storage and normal log contain no raw bearer credential")
        return 0
    except AssertionError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    except (OSError, RuntimeError, UnicodeError, ValueError,
            json.JSONDecodeError, KeyError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
