#!/usr/bin/env python3
"""Tests for create_release_manifest.py."""

import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT_PATH = Path(__file__).with_name("create_release_manifest.py")
SPEC = importlib.util.spec_from_file_location("create_release_manifest", SCRIPT_PATH)
CREATE_RELEASE_MANIFEST = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(CREATE_RELEASE_MANIFEST)


class CreateReleaseManifestTest(unittest.TestCase):
    def test_manifest_has_settled_schema_and_jar_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            jar_path = Path(directory) / "mc-remote-1.21.11-2301.0.0b7.jar"
            jar_bytes = b"candidate jar bytes\n"
            jar_path.write_bytes(jar_bytes)

            manifest = CREATE_RELEASE_MANIFEST.create_manifest(
                release_tag="v1.21.11-2301.0.0b7",
                source_commit="A" * 40,
                jar_path=jar_path,
            )

        self.assertEqual(
            {
                "schema": "mc-remote.release-manifest",
                "schema_version": 1,
                "release_tag": "v1.21.11-2301.0.0b7",
                "source_commit": "a" * 40,
                "artifacts": [
                    {
                        "role": "jar",
                        "kind": "https-file",
                        "file": "mc-remote-1.21.11-2301.0.0b7.jar",
                        "sha256": hashlib.sha256(jar_bytes).hexdigest(),
                    }
                ],
            },
            manifest,
        )

    def test_output_is_deterministic_and_newline_terminated(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar_path = root / "candidate.jar"
            jar_path.write_bytes(b"same bytes")
            manifest = CREATE_RELEASE_MANIFEST.create_manifest(
                release_tag="v2301.0.0b8",
                source_commit="1" * 40,
                jar_path=jar_path,
            )
            first = root / "first.json"
            second = root / "second.json"

            CREATE_RELEASE_MANIFEST.write_manifest(manifest, first)
            CREATE_RELEASE_MANIFEST.write_manifest(manifest, second)

            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertTrue(first.read_bytes().endswith(b"\n"))
            self.assertEqual(manifest, json.loads(first.read_text(encoding="utf-8")))

    def test_rejects_abbreviated_source_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            jar_path = Path(directory) / "candidate.jar"
            jar_path.write_bytes(b"bytes")

            with self.assertRaisesRegex(ValueError, "full 40-character"):
                CREATE_RELEASE_MANIFEST.create_manifest(
                    release_tag="v2301.0.0b8",
                    source_commit="abc1234",
                    jar_path=jar_path,
                )

    def test_rejects_missing_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "does not exist"):
                CREATE_RELEASE_MANIFEST.create_manifest(
                    release_tag="v2301.0.0b8",
                    source_commit="2" * 40,
                    jar_path=Path(directory) / "missing.jar",
                )


if __name__ == "__main__":
    unittest.main()
