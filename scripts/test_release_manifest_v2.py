"""Publication bindings, malformed manifests and compatibility record handling."""

from copy import deepcopy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from create_release_manifest import create_manifest
from prepare_minecraft_verification import prepare_record
from prepare_release_body import update_body, BEGIN, END
from release_manifest_v2 import (load_json, sha256_file, validate_candidate_files,
                                 validate_locked_schema, validate_v2,
                                 verification_filename, load_locked_contracts,
                                 check_shared_manifest)


class ReleaseManifestV2Test(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = "a" * 40
        self.declaration = self.root / "minecraft-targets.json"
        self.declaration.write_text(json.dumps({"schema": "mc-remote.minecraft-targets", "schema_version": 1,
                                               "minecraft_versions": ["1.21.11", "26.2"]}) + "\n")
        self.jar = self.root / "mc-remote-2320.0.0b10.jar"
        with ZipFile(self.jar, "w") as jar:
            jar.writestr("minecraft-targets.json", self.declaration.read_bytes())
        self.records = []
        for version, build, java in (("1.21.11", 130, "21.0.12.1+1-1-24.04.4-Ubuntu"),
                                     ("26.2", 132, "25.0.4.1+1-LTS")):
            path = self.root / verification_filename(version)
            self.write_json(path, {"schema": "mc-remote.minecraft-verification", "schema_version": 1,
                                   "source_commit": self.source, "declaration_sha256": sha256_file(self.declaration),
                                   "minecraft_version": version, "paper_build": build, "server_sha256": "b" * 64,
                                   "java_version": java, "jar_sha256": sha256_file(self.jar), "result": "PASS"})
            self.records.append(path)

    @staticmethod
    def write_json(path, data):
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def manifest(self, records=None):
        return create_manifest("v2320.0.0b10", self.source, self.jar, schema_version=2,
                               declaration_path=self.declaration,
                               verification_records=self.records if records is None else records)

    def test_binds_measured_runtime_same_jar_and_ordered_declaration(self):
        manifest = self.manifest(list(reversed(self.records)))
        self.assertEqual(2, manifest["schema_version"])
        jar = manifest["artifacts"][0]
        self.assertEqual(self.jar.stat().st_size, jar["bytes"])
        checks = manifest["minecraft_compatibility"]["verifications"]
        self.assertEqual(["1.21.11", "26.2"], [c["minecraft_version"] for c in checks])
        self.assertEqual("21.0.12.1+1-1-24.04.4-Ubuntu", checks[0]["java_version"])
        self.assertEqual("25.0.4.1+1-LTS", checks[1]["java_version"])
        self.assertTrue(all(c["jar_sha256"] == jar["sha256"] for c in checks))
        self.assertEqual(self.manifest(), manifest)
        self.assertEqual(1, len(manifest["artifacts"]))

    def test_rejects_missing_or_duplicate_pass_and_failed_record(self):
        for records in (self.records[:1], self.records + self.records[:1], []):
            with self.subTest(records=records), self.assertRaises(ValueError):
                self.manifest(records)
        data = load_json(self.records[0])
        data["result"] = "FAIL"
        self.write_json(self.records[0], data)
        with self.assertRaisesRegex(ValueError, "PASS"):
            self.manifest()

    def test_rejects_different_record_source_declaration_or_jar(self):
        original = load_json(self.records[0])
        for key, value in (("source_commit", "c" * 40), ("declaration_sha256", "c" * 64), ("jar_sha256", "c" * 64)):
            with self.subTest(key=key):
                data = {**original, key: value}
                self.write_json(self.records[0], data)
                with self.assertRaises(ValueError):
                    self.manifest()

    def test_same_versions_with_different_declaration_bytes_do_not_pass(self):
        self.declaration.write_bytes(self.declaration.read_bytes() + b" ")
        for path in self.records:
            data = load_json(path)
            data["declaration_sha256"] = sha256_file(self.declaration)
            self.write_json(path, data)
        with self.assertRaisesRegex(ValueError, "bundled"):
            self.manifest()

    def test_rechecks_asset_digest_and_actual_record_identity(self):
        manifest = self.manifest()
        data = load_json(self.records[0])
        data["paper_build"] += 1
        self.write_json(self.records[0], data)
        with self.assertRaisesRegex(ValueError, "record digest"):
            validate_candidate_files(manifest, self.jar, self.declaration, self.root)
        manifest["minecraft_compatibility"]["verifications"][0]["record"]["sha256"] = sha256_file(self.records[0])
        with self.assertRaisesRegex(ValueError, "measured identity"):
            validate_candidate_files(manifest, self.jar, self.declaration, self.root)

    def test_rechecks_candidate_jar_and_declaration_files(self):
        manifest = self.manifest()
        original = self.jar.read_bytes()
        self.jar.write_bytes(original + b"modified")
        with self.assertRaisesRegex(ValueError, "jar filename, bytes or digest"):
            validate_candidate_files(manifest, self.jar, self.declaration, self.root)
        self.jar.write_bytes(original)
        self.declaration.write_bytes(self.declaration.read_bytes() + b" ")
        with self.assertRaisesRegex(ValueError, "declaration file digest"):
            validate_candidate_files(manifest, self.jar, self.declaration, self.root)

    def test_rejects_duplicate_bundled_declaration(self):
        with ZipFile(self.jar, "a") as jar:
            import warnings
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                jar.writestr("minecraft-targets.json", self.declaration.read_bytes())
        for path in self.records:
            data = load_json(path)
            data["jar_sha256"] = sha256_file(self.jar)
            self.write_json(path, data)
        with self.assertRaisesRegex(ValueError, "bundled"):
            self.manifest()

    def test_unknown_versions_and_fields_are_rejected_without_v1_fallback(self):
        for version in (1, 3, True, "2", None):
            with self.subTest(version=version), self.assertRaises(ValueError):
                validate_v2({**self.manifest(), "schema_version": version})
        for location in ("top", "artifact", "declaration", "verification", "record"):
            manifest = self.manifest()
            compat = manifest["minecraft_compatibility"]
            target = {"top": manifest, "artifact": manifest["artifacts"][0], "declaration": compat["declaration"],
                      "verification": compat["verifications"][0], "record": compat["verifications"][0]["record"]}[location]
            target["unknown"] = "value"
            with self.subTest(location=location), self.assertRaisesRegex(ValueError, "unknown fields"):
                validate_v2(manifest)

    def test_retains_v1_optional_fields_and_accepts_three_os_variants(self):
        manifest = self.manifest()
        manifest["bundled_wirescope_source_commit"] = "d" * 40
        manifest["artifacts"][0]["artifact_version"] = "2320.0.0b10"
        for os, arch in (("windows", "x64"), ("macos", "arm64"), ("linux", "x64")):
            manifest["artifacts"].append({"role": "scratch-local", "kind": "https-file", "file": f"{os}.zip",
                                          "sha256": "e" * 64, "bytes": 0, "os": os, "arch": arch})
        manifest["artifacts"].append({"role": "bridge", "kind": "oci", "locator": "ghcr.io/example/bridge",
                                      "digest": "sha256:" + "f" * 64, "artifact_version": "b10"})
        validate_v2(manifest)

    def test_rejects_missing_invalid_or_boolean_bytes(self):
        for value in (-1, True, 1.5, "12", None):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "bytes"):
                manifest = self.manifest()
                manifest["artifacts"][0]["bytes"] = value
                validate_v2(manifest)
        manifest = self.manifest()
        del manifest["artifacts"][0]["bytes"]
        with self.assertRaises(ValueError):
            validate_v2(manifest)

    def test_rejects_partial_null_unknown_and_mixed_os_arch(self):
        for pair in ({"os": "linux"}, {"arch": "x64"}, {"os": None, "arch": "x64"},
                     {"os": "linux", "arch": None}, {"os": "Linux", "arch": "x64"},
                     {"os": "linux", "arch": "amd64"}, {"os": "", "arch": ""}):
            manifest = self.manifest()
            manifest["artifacts"].append({"role": "extra", "kind": "https-file", "file": "extra.zip",
                                          "sha256": "e" * 64, "bytes": 1, **pair})
            with self.subTest(pair=pair), self.assertRaises(ValueError):
                validate_v2(manifest)
        manifest = self.manifest()
        plain = {"role": "extra", "kind": "https-file", "file": "a.zip", "sha256": "e" * 64, "bytes": 1}
        manifest["artifacts"] += [plain, {**plain, "file": "b.zip", "os": "linux", "arch": "x64"}]
        with self.assertRaisesRegex(ValueError, "mixed"):
            validate_v2(manifest)

    def test_rejects_same_key_with_different_file_hash_or_kind(self):
        manifest = self.manifest()
        manifest["artifacts"].append({**manifest["artifacts"][0], "file": "other.jar", "sha256": "e" * 64})
        with self.assertRaisesRegex(ValueError, "duplicate artifact key"):
            validate_v2(manifest)
        manifest = self.manifest()
        manifest["artifacts"].append({"role": "jar", "kind": "oci", "locator": "ghcr.io/example/jar", "digest": "sha256:" + "e" * 64})
        with self.assertRaisesRegex(ValueError, "duplicate artifact key"):
            validate_v2(manifest)

    def test_rejects_incomplete_or_inconsistent_compatibility(self):
        for mutation in ("absent", "duplicate", "extra-version", "different-jar", "not-pass", "empty-declaration"):
            manifest = self.manifest()
            compat = manifest["minecraft_compatibility"]
            if mutation == "absent":
                del manifest["minecraft_compatibility"]
            elif mutation == "duplicate":
                compat["verifications"].append(deepcopy(compat["verifications"][0]))
            elif mutation == "extra-version":
                compat["declaration"]["minecraft_versions"].append("26.3")
            elif mutation == "different-jar":
                compat["verifications"][0]["jar_sha256"] = "e" * 64
            elif mutation == "not-pass":
                compat["verifications"][0]["result"] = "FAIL"
            else:
                compat["declaration"]["minecraft_versions"] = []
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                validate_v2(manifest)

    def test_record_reference_is_basename_only_and_not_an_artifact(self):
        for name in ("../outside.json", "https://example.com/report.json", "reports/report.json", "C:\\report.json"):
            manifest = self.manifest()
            manifest["minecraft_compatibility"]["verifications"][0]["record"]["file"] = name
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "basename"):
                validate_v2(manifest)
        manifest = self.manifest()
        reference = manifest["minecraft_compatibility"]["verifications"][0]["record"]
        manifest["artifacts"].append({"role": "record", "kind": "https-file", **reference, "bytes": 1})
        with self.assertRaisesRegex(ValueError, "not be listed"):
            validate_v2(manifest)

    def test_duplicate_json_fields_and_non_json_constants_are_rejected(self):
        path = self.root / "bad.json"
        for content in ('{"schema_version":1,"schema_version":2}', '{"nested":{"x":1,"x":2}}', '{"x":NaN}'):
            path.write_text(content)
            with self.subTest(content=content), self.assertRaises(ValueError):
                load_json(path)

    def locked_schema(self, schema):
        path = self.root / "release-manifest-v2.schema.json"
        self.write_json(path, schema)
        lock_path = self.root / "release-manifest-lock.json"
        self.write_json(lock_path, {"repository": "Naohiro2g/minecraft-remote-tooling", "source_commit": "c" * 40,
                                    "schema": {"path": "schemas/release-manifest-v2.schema.json", "local_path": path.name,
                                               "bytes": path.stat().st_size, "sha256": sha256_file(path)}})
        return lock_path, path

    def test_missing_schema_lock_blocks_publication(self):
        with self.assertRaisesRegex(ValueError, "lock is missing"):
            validate_locked_schema(self.manifest(), self.root / "missing.json")

    def test_pinned_schema_engine_and_digest_are_both_required(self):
        # This miniature schema isolates the loader from the shared contract.
        lock, schema = self.locked_schema({"$schema": "https://json-schema.org/draft/2020-12/schema",
                                          "type": "object", "properties": {"schema_version": {"const": 2}}})
        validate_locked_schema(self.manifest(), lock)
        with self.assertRaisesRegex(ValueError, "Schema validation failed"):
            validate_locked_schema({**self.manifest(), "schema_version": 1}, lock)
        schema.write_bytes(schema.read_bytes() + b" ")
        with self.assertRaisesRegex(ValueError, "bytes/digest"):
            validate_locked_schema(self.manifest(), lock)

    def test_pinned_schema_never_fetches_remote_references(self):
        lock, _ = self.locked_schema({"$schema": "https://json-schema.org/draft/2020-12/schema",
                                     "$ref": "https://example.invalid/not-a-local-contract.json"})
        with self.assertRaisesRegex(ValueError, "Schema validation failed"):
            validate_locked_schema(self.manifest(), lock)

    def test_source_pin_and_local_schema_path_are_checked(self):
        lock, _ = self.locked_schema({"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object"})
        original = load_json(lock)
        for key, value in (("repository", "other/repo"), ("source_commit", "main")):
            self.write_json(lock, {**original, key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_locked_schema(self.manifest(), lock)
        changed = deepcopy(original)
        changed["schema"]["local_path"] = "../outside.json"
        self.write_json(lock, changed)
        with self.assertRaisesRegex(ValueError, "escapes"):
            validate_locked_schema(self.manifest(), lock)

    def test_actual_object_declaration_passes_pinned_schema_and_reference_bytes(self):
        contracts = load_locked_contracts(Path(__file__).resolve().parents[1] / "release/release-manifest-lock.json")
        manifest = self.manifest()
        contents = {"release/minecraft-targets.json": self.declaration.read_bytes(),
                    self.jar.name: self.jar.read_bytes()}
        contents.update({path.name: path.read_bytes() for path in self.records})
        self.assertEqual({"valid": True, "reason": None, "stage": None},
                         check_shared_manifest(manifest, contracts, contents, self.declaration.read_bytes()))
        changed = dict(contents)
        changed["release/minecraft-targets.json"] += b" "
        self.assertEqual("declaration_sha256_mismatch",
                         check_shared_manifest(manifest, contracts, changed)["reason"])

    def test_rejects_noncanonical_declarations_without_reading_them_as_objects(self):
        contracts = load_locked_contracts(Path(__file__).resolve().parents[1] / "release/release-manifest-lock.json")
        valid = load_json(self.declaration)
        invalid = [valid["minecraft_versions"], None,
                   {**valid, "unknown": "field"}, {**valid, "schema": "other"},
                   {**valid, "schema_version": 2}, {**valid, "schema_version": "1"},
                   {**valid, "schema_version": True},
                   {**valid, "minecraft_versions": []},
                   {**valid, "minecraft_versions": ["1.21.11", "1.21.11"]},
                   {**valid, "minecraft_versions": [None]},
                   {**valid, "minecraft_versions": [""]}]
        original_manifest = self.manifest()
        for value in invalid:
            with self.subTest(declaration=value):
                raw = (json.dumps(value) + "\n").encode()
                manifest = deepcopy(original_manifest)
                manifest["minecraft_compatibility"]["declaration"]["sha256"] = hashlib.sha256(raw).hexdigest()
                result = check_shared_manifest(manifest, contracts, {"release/minecraft-targets.json": raw}, raw)
                self.assertEqual({"valid": False, "reason": "declaration_content_invalid", "stage": "content"}, result)
                self.declaration.write_bytes(raw)
                with self.assertRaises(ValueError):
                    self.manifest()
        self.write_json(self.declaration, valid)

    def test_combined_record_preserves_identities_and_redacts_tokens(self):
        meta = load_json(self.records[0])
        pulse = {"schema": "mc-remote.local-compatibility-pulse", "schema_version": 1, "status": "PASS", "restart_only": False,
                 "source_commit": self.source, "minecraft_version": "1.21.11", "paper_build": 130,
                 "paper_server_sha256": meta["server_sha256"], "java_version": meta["java_version"],
                 "jar_sha256": meta["jar_sha256"], "declaration_sha256": meta["declaration_sha256"],
                 "declared_versions": ["1.21.11", "26.2"], "transcript": [{"token": "mcrs_testsecret"}]}
        restart = {**pulse, "restart_only": True}
        lifecycle = {"source_commit": self.source, "minecraft_version": "1.21.11", "jar_sha256": meta["jar_sha256"],
                     "observations": [{"enabled": True, "disabled": True, "ready": True, "auth_enforcement_on": True}] * 2}
        combined = prepare_record(pulse, restart, lifecycle)
        self.assertEqual(meta["java_version"], combined["java_version"])
        self.assertNotIn("mcrs_testsecret", json.dumps(combined))
        for bad in ({**restart, "status": "FAIL"}, {**restart, "source_commit": "c" * 40}, {**restart, "java_version": "other"}):
            with self.assertRaises(ValueError):
                prepare_record(pulse, bad, lifecycle)


class ReleaseBodyTest(unittest.TestCase):
    def test_add_and_replace_generated_section_preserve_human_text(self):
        section = f"{BEGIN}\n対応Minecraft: 1.21.11, 26.2\n{END}\n"
        body = "人間のリリース説明\n"
        appended = update_body(body, section)
        self.assertEqual(body.rstrip() + "\n\n" + section, appended)
        self.assertEqual(appended, update_body(appended, section))
        prior = f"前文\n{BEGIN}\n対応Minecraft: old\n{END}\n後文\n"
        self.assertEqual("前文\n" + section + "後文\n", update_body(prior, section))

    def test_refuses_incomplete_or_duplicated_markers(self):
        section = f"{BEGIN}\nx\n{END}\n"
        for body in (BEGIN, END, END + BEGIN, section * 2):
            with self.subTest(body=body), self.assertRaises(ValueError):
                update_body(body, section)


class SharedReleaseManifestFixtureTest(unittest.TestCase):
    lock_path = Path(__file__).resolve().parents[1] / "release/release-manifest-lock.json"

    def test_all_issued_cases_match_verdict_stage_and_reason(self):
        contracts = load_locked_contracts(self.lock_path)
        fixture = contracts["fixtures"]
        self.assertEqual("mc-remote.release-manifest.fixtures", fixture["schema"])
        self.assertEqual(1, fixture["schema_version"])
        cases = fixture["cases"]
        self.assertEqual(len(cases), len({case["id"] for case in cases}))
        self.assertEqual(85, len(cases))
        self.assertEqual(10, sum(case["expected"]["valid"] for case in cases))
        for case in cases:
            with self.subTest(case=case["id"]):
                self.assertEqual(case["expected"],
                                 check_shared_manifest(case["manifest"], contracts,
                                                       case.get("contents"), case.get("jar_declaration")))

    def test_legacy_schema_retains_issuer_source_digest(self):
        contracts = load_locked_contracts(self.lock_path)
        lock = load_json(self.lock_path)
        self.assertEqual(contracts["fixtures"]["legacy_v1_schema_source"]["sha256"],
                         lock["legacy_v1_schema"]["sha256"])

    def test_actual_declaration_fixture_preserves_source_bytes(self):
        contracts = load_locked_contracts(self.lock_path)
        fixture = contracts["fixtures"]
        source = fixture["declaration_file_source"]
        case = next(case for case in fixture["cases"] if case["id"] == source["case_id"])
        raw = case["contents"][source["path"]].encode("utf-8")
        self.assertEqual(source["bytes"], len(raw))
        self.assertEqual(source["sha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(raw, case["jar_declaration"].encode("utf-8"))

    def test_tampered_schema_fixture_legacy_schema_or_license_blocks_validation(self):
        import shutil
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            shutil.copytree(self.lock_path.parent / "contracts", root / "contracts")
            local_lock = root / self.lock_path.name
            shutil.copyfile(self.lock_path, local_lock)
            lock = load_json(local_lock)
            manifest = load_locked_contracts(local_lock)["fixtures"]["cases"][2]["manifest"]
            for label in ("schema", "fixtures", "legacy_v1_schema", "legacy_v1_license"):
                path = root / lock[label]["local_path"]
                original = path.read_bytes()
                path.write_bytes(original + b" ")
                with self.subTest(input=label), self.assertRaisesRegex(ValueError, "bytes/digest"):
                    validate_locked_schema(manifest, local_lock)
                path.write_bytes(original)


if __name__ == "__main__":
    unittest.main()
