#!/usr/bin/env python3
"""McRemote's v2 producer checks; the shared JSON Schema belongs to tooling.

The structural checks also support local preflight before the shared schema arrives.
Publication additionally requires the digest-pinned, local Draft 2020-12 schema.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path, PurePosixPath
import re
from typing import Any
from zipfile import BadZipFile, ZipFile


SCHEMA = "mc-remote.release-manifest"
DECLARATION_PATH = "release/minecraft-targets.json"
VERIFICATION_FIELDS = (
    "minecraft_version", "paper_build", "server_sha256", "java_version",
    "jar_sha256", "result",
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def decode_json(raw: bytes | str) -> Any:
    if isinstance(raw, bytes):
        raw = raw.decode("utf-8")
    def unique_keys(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON field: {key}")
            result[key] = value
        return result

    def invalid_constant(value):
        raise ValueError(f"non-JSON numeric constant: {value}")

    return json.loads(raw, object_pairs_hook=unique_keys,
                      parse_constant=invalid_constant)


def load_json(path: Path) -> Any:
    return decode_json(path.read_bytes())


def check_manifest_semantics(manifest: dict) -> dict:
    """Whole-document checks after the version's Schema, with fixture reason IDs."""
    def rejected(reason):
        return {"valid": False, "reason": reason, "stage": "semantic"}

    keys, variants = set(), {}
    for artifact in manifest["artifacts"]:
        variant = "os" in artifact
        key = artifact["role"] if manifest["schema_version"] == 1 else (
            artifact["role"], artifact.get("os"), artifact.get("arch"))
        if key in keys:
            return rejected("duplicate_role" if manifest["schema_version"] == 1 else "duplicate_artifact_key")
        keys.add(key)
        if manifest["schema_version"] == 2 and artifact["role"] in variants and variants[artifact["role"]] != variant:
            return rejected("mixed_artifact_variants")
        variants[artifact["role"]] = variant
    compatibility = manifest.get("minecraft_compatibility")
    if compatibility:
        jars = [a for a in manifest["artifacts"] if a["role"] == "jar"]
        if len(jars) != 1 or jars[0]["kind"] != "https-file":
            return rejected("jar_artifact_invalid")
        verified = [v["minecraft_version"] for v in compatibility["verifications"]]
        if len(set(verified)) != len(verified):
            return rejected("duplicate_verification_version")
        if set(verified) != set(compatibility["declaration"]["minecraft_versions"]):
            return rejected("minecraft_version_set_mismatch")
        if any(v["jar_sha256"] != jars[0]["sha256"] for v in compatibility["verifications"]):
            return rejected("jar_sha256_mismatch")
        files = {a["file"] for a in manifest["artifacts"] if a["kind"] == "https-file"}
        if any(v["record"]["file"] in files for v in compatibility["verifications"]):
            return rejected("record_listed_as_artifact")
    return {"valid": True, "reason": None, "stage": None}


def require_semantics(manifest: dict) -> None:
    result = check_manifest_semantics(manifest)
    if not result["valid"]:
        messages = {
            "duplicate_artifact_key": "duplicate artifact key",
            "mixed_artifact_variants": "mixed variant and unqualified artifacts",
            "duplicate_verification_version": "duplicate Minecraft verification",
            "minecraft_version_set_mismatch": "declaration and verification version sets differ",
            "jar_sha256_mismatch": "verification jar_sha256 differs from jar artifact",
            "record_listed_as_artifact": "verification records must not be listed in artifacts",
        }
        raise ValueError(messages.get(result["reason"], result["reason"]))


def check_manifest_contents(manifest: dict, contents: dict, jar_declaration: bytes | str | None = None) -> dict:
    """Check raw reference bytes; McRemote also retains its existing object declaration."""
    def rejected(reason):
        return {"valid": False, "reason": reason, "stage": "content"}

    def raw(value):
        return value.encode("utf-8") if isinstance(value, str) else bytes(value)

    def digest(value):
        return hashlib.sha256(value).hexdigest()

    for artifact in manifest["artifacts"]:
        if artifact["kind"] != "https-file" or artifact["file"] not in contents:
            continue
        body = raw(contents[artifact["file"]])
        if manifest["schema_version"] == 2 and len(body) != artifact["bytes"]:
            return rejected("artifact_bytes_mismatch")
        if digest(body) != artifact["sha256"]:
            return rejected("artifact_sha256_mismatch")
    compatibility = manifest.get("minecraft_compatibility")
    if not compatibility:
        return {"valid": True, "reason": None, "stage": None}
    declaration = compatibility["declaration"]
    if declaration["path"] not in contents:
        return rejected("reference_content_missing")
    body = raw(contents[declaration["path"]])
    if digest(body) != declaration["sha256"]:
        return rejected("declaration_sha256_mismatch")
    try:
        declared = decode_json(body)
    except (ValueError, UnicodeError):
        return rejected("declaration_content_invalid")
    # The issued fixtures use a bare array. The actual McRemote declaration is
    # mc-remote.minecraft-targets v1; do not rewrite these already-bundled bytes.
    if isinstance(declared, dict) and declared.get("schema") == "mc-remote.minecraft-targets" and type(declared.get("schema_version")) is int and declared["schema_version"] == 1:
        declared = declared.get("minecraft_versions")
    if not isinstance(declared, list) or any(not isinstance(v, str) for v in declared):
        return rejected("declaration_versions_mismatch")
    expected = declaration["minecraft_versions"]
    if len(declared) != len(expected) or len(set(declared)) != len(declared) or set(declared) != set(expected):
        return rejected("declaration_versions_mismatch")
    if jar_declaration is not None and body != raw(jar_declaration):
        return rejected("jar_declaration_bytes_mismatch")
    for verification in compatibility["verifications"]:
        record = verification["record"]
        if record["file"] not in contents:
            return rejected("reference_content_missing")
        if digest(raw(contents[record["file"]])) != record["sha256"]:
            return rejected("record_sha256_mismatch")
    return {"valid": True, "reason": None, "stage": None}


def fields(value, required, optional=(), label="object"):
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be an object")
    missing = set(required) - value.keys()
    unknown = value.keys() - set(required) - set(optional)
    if missing or unknown:
        raise ValueError(f"{label}: missing fields {sorted(missing)}; unknown fields {sorted(unknown)}")


def string(value, label):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} must be a nonempty string")


def integer(value, minimum, label):
    if type(value) is not int or value < minimum:
        raise ValueError(f"{label} must be an integer >= {minimum}")


def hex_string(value, length, label):
    if not isinstance(value, str) or re.fullmatch(rf"[0-9a-f]{{{length}}}", value) is None:
        raise ValueError(f"{label} must be {length} lowercase hexadecimal characters")


def basename(value, label):
    string(value, label)
    if value in (".", "..") or any(c in value for c in ("/", "\\", ":", "\n", "\r")):
        raise ValueError(f"{label} must be a Release asset basename")


def versions(value, label):
    if not isinstance(value, list) or not value:
        raise ValueError(f"{label} must be a nonempty array")
    for version in value:
        string(version, label)
    if len(set(value)) != len(value):
        raise ValueError(f"{label} must not contain duplicate versions")


def verification_filename(version: str) -> str:
    # Target declarations used by McRemote have numeric MC versions.
    if re.fullmatch(r"\d+\.\d+(?:\.\d+)?", version) is None:
        raise ValueError("unsupported Minecraft version in verification filename")
    return f"mc-remote-verification-{version}.json"


def validate_v2(manifest: dict[str, Any]) -> None:
    """Validate the settled v2 shape and whole-document semantic constraints."""
    fields(manifest, ("schema", "schema_version", "release_tag", "source_commit", "artifacts"),
           ("bundled_wirescope_source_commit", "minecraft_compatibility"), "manifest")
    if manifest["schema"] != SCHEMA or type(manifest["schema_version"]) is not int or manifest["schema_version"] != 2:
        raise ValueError("expected release manifest schema_version 2; no v1 fallback")
    string(manifest["release_tag"], "release_tag")
    hex_string(manifest["source_commit"], 40, "source_commit")
    if "bundled_wirescope_source_commit" in manifest:
        hex_string(manifest["bundled_wirescope_source_commit"], 40, "bundled_wirescope_source_commit")
    if not isinstance(manifest["artifacts"], list):
        raise ValueError("artifacts must be an array")
    jars = []
    for artifact in manifest["artifacts"]:
        if not isinstance(artifact, dict):
            raise ValueError("artifact must be an object")
        kind = artifact.get("kind")
        if kind == "https-file":
            fields(artifact, ("role", "kind", "file", "sha256", "bytes"),
                   ("os", "arch", "artifact_version"), "https-file artifact")
            basename(artifact["file"], "artifact.file")
            hex_string(artifact["sha256"], 64, "artifact.sha256")
            integer(artifact["bytes"], 0, "artifact.bytes")
            if ("os" in artifact) != ("arch" in artifact):
                raise ValueError("os and arch must be supplied together")
            if "os" in artifact:
                if artifact["os"] not in ("windows", "macos", "linux") or artifact["arch"] not in ("x64", "arm64"):
                    raise ValueError("unknown or null os/arch")
        elif kind == "oci":
            fields(artifact, ("role", "kind", "locator", "digest"), ("artifact_version",), "oci artifact")
            string(artifact["locator"], "artifact.locator")
            if not isinstance(artifact["digest"], str) or not artifact["digest"].startswith("sha256:"):
                raise ValueError("artifact.digest must be a sha256 digest")
            hex_string(artifact["digest"][7:], 64, "artifact.digest")
        else:
            raise ValueError("unknown artifact kind")
        string(artifact["role"], "artifact.role")
        if "artifact_version" in artifact:
            string(artifact["artifact_version"], "artifact_version")
        role = artifact["role"]
        if role == "jar":
            jars.append(artifact)
    compatibility = manifest.get("minecraft_compatibility")
    if not jars and "minecraft_compatibility" not in manifest:
        require_semantics(manifest)
        return
    fields(compatibility, ("declaration", "verifications"), label="minecraft_compatibility")
    declaration = compatibility["declaration"]
    fields(declaration, ("path", "sha256", "minecraft_versions"), label="declaration")
    string(declaration["path"], "declaration.path")
    path = PurePosixPath(declaration["path"])
    if path.is_absolute() or ".." in path.parts or "\\" in declaration["path"] or str(path) != declaration["path"]:
        raise ValueError("declaration.path must be a producer-repository relative path")
    hex_string(declaration["sha256"], 64, "declaration.sha256")
    versions(declaration["minecraft_versions"], "declaration.minecraft_versions")
    checks = compatibility["verifications"]
    if not isinstance(checks, list):
        raise ValueError("verifications must be an array")
    for check in checks:
        fields(check, (*VERIFICATION_FIELDS, "record"), label="verification")
        string(check["minecraft_version"], "minecraft_version")
        integer(check["paper_build"], 1, "paper_build")
        string(check["java_version"], "java_version")
        hex_string(check["server_sha256"], 64, "server_sha256")
        hex_string(check["jar_sha256"], 64, "jar_sha256")
        if check["result"] != "PASS":
            raise ValueError("verification result must be PASS")
        fields(check["record"], ("file", "sha256"), label="record")
        basename(check["record"]["file"], "record.file")
        hex_string(check["record"]["sha256"], 64, "record.sha256")
    require_semantics(manifest)
    if len(jars) != 1 or jars[0]["kind"] != "https-file" or "os" in jars[0]:
        raise ValueError("minecraft_compatibility requires exactly one unqualified https-file jar")


def read_verification_record(path: Path, source_commit: str, declaration_sha256: str) -> dict:
    data = load_json(path)
    if not isinstance(data, dict) or data.get("schema") != "mc-remote.minecraft-verification" or type(data.get("schema_version")) is not int or data["schema_version"] != 1:
        raise ValueError("expected a McRemote Minecraft verification record")
    if data.get("source_commit") != source_commit:
        raise ValueError("record source_commit differs from candidate source")
    if data.get("declaration_sha256") != declaration_sha256:
        raise ValueError("record declaration_sha256 differs from candidate declaration")
    if not all(k in data for k in VERIFICATION_FIELDS):
        raise ValueError("verification record is missing measured identity fields")
    return {key: data[key] for key in VERIFICATION_FIELDS}


def validate_candidate_files(manifest: dict, jar_path: Path, declaration_path: Path,
                             record_root: Path) -> None:
    validate_v2(manifest)
    jar = next(a for a in manifest["artifacts"] if a["role"] == "jar")
    if jar_path.name != jar["file"] or jar_path.stat().st_size != jar["bytes"] or sha256_file(jar_path) != jar["sha256"]:
        raise ValueError("candidate jar filename, bytes or digest differs")
    declaration = manifest["minecraft_compatibility"]["declaration"]
    raw = declaration_path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != declaration["sha256"]:
        raise ValueError("declaration file digest differs")
    data = load_json(declaration_path)
    fields(data, ("schema", "schema_version", "minecraft_versions"), label="target declaration")
    if data["schema"] != "mc-remote.minecraft-targets" or type(data["schema_version"]) is not int or data["schema_version"] != 1:
        raise ValueError("invalid target declaration schema")
    if data["minecraft_versions"] != declaration["minecraft_versions"]:
        raise ValueError("declaration file versions differ")
    try:
        with ZipFile(jar_path) as jar_file:
            if jar_file.namelist().count("minecraft-targets.json") != 1 or jar_file.read("minecraft-targets.json") != raw:
                raise ValueError("declaration bytes differ from the bundled jar declaration")
    except (BadZipFile, KeyError) as error:
        raise ValueError("candidate jar has no valid bundled declaration") from error
    for check in manifest["minecraft_compatibility"]["verifications"]:
        path = record_root / check["record"]["file"]
        if sha256_file(path) != check["record"]["sha256"]:
            raise ValueError("verification record digest differs")
        measured = read_verification_record(path, manifest["source_commit"], declaration["sha256"])
        if measured != {key: check[key] for key in VERIFICATION_FIELDS}:
            raise ValueError("verification record measured identity differs from manifest")
    contents = {declaration["path"]: raw}
    contents.update({check["record"]["file"]: (record_root / check["record"]["file"]).read_bytes()
                     for check in manifest["minecraft_compatibility"]["verifications"]})
    result = check_manifest_contents(manifest, contents, raw)
    if not result["valid"]:
        raise ValueError(f"candidate reference content rejected: {result['reason']}")


def load_locked_contracts(lock_path: Path) -> dict:
    """Verify every declared local input before using Schema or shared fixtures."""
    if not lock_path.is_file():
        raise ValueError("fixed tooling release-manifest schema lock is missing; publication is not ready")
    lock = load_json(lock_path)
    fields(lock, ("repository", "source_commit", "schema"),
           ("fixtures", "legacy_v1_schema", "legacy_v1_license"), "schema lock")
    if lock["repository"] != "Naohiro2g/minecraft-remote-tooling":
        raise ValueError("schema lock must reference minecraft-remote-tooling")
    hex_string(lock["source_commit"], 40, "schema lock source_commit")
    paths = {"schema": "schemas/release-manifest-v2.schema.json",
             "fixtures": "schemas/fixtures/release-manifest-v2.json",
             "legacy_v1_schema": "schemas/fixtures/release-manifest-v1.schema.json",
             "legacy_v1_license": "schemas/fixtures/STACK-LICENSE"}
    inputs = {}
    for label, upstream in paths.items():
        if label not in lock:
            continue
        entry = lock[label]
        fields(entry, ("path", "local_path", "bytes", "sha256"), label=f"locked {label}")
        if entry["path"] != upstream:
            raise ValueError(f"unexpected upstream {label} path")
        integer(entry["bytes"], 1, f"{label} bytes")
        hex_string(entry["sha256"], 64, f"{label} sha256")
        string(entry["local_path"], f"{label} local_path")
        local = (lock_path.parent / entry["local_path"]).resolve()
        if not local.is_relative_to(lock_path.parent.resolve()):
            raise ValueError(f"local {label} path escapes lock directory")
        raw = local.read_bytes()
        if len(raw) != entry["bytes"] or hashlib.sha256(raw).hexdigest() != entry["sha256"]:
            raise ValueError(f"fixed {label} bytes/digest differ from lock")
        inputs[label] = raw if label == "legacy_v1_license" else decode_json(raw)
    return inputs


def schema_validator(schema: dict):
    if not isinstance(schema, dict) or schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
        raise ValueError("fixed schema must be Draft 2020-12")
    try:
        from jsonschema import Draft202012Validator
        from referencing import Registry
        Draft202012Validator.check_schema(schema)
        return Draft202012Validator(schema, registry=Registry())
    except ImportError as error:
        raise ValueError("install scripts/requirements-release.txt to validate the fixed schema") from error
    except Exception as error:
        raise ValueError(f"fixed JSON Schema validation failed: {error}") from error


def validate_locked_schema(manifest: dict, lock_path: Path) -> None:
    """Use only locally pinned bytes; never retrieve main or remote $refs."""
    schema = load_locked_contracts(lock_path)["schema"]
    try:
        schema_validator(schema).validate(manifest)
    except Exception as error:
        raise ValueError(f"fixed JSON Schema validation failed: {error}") from error


def check_shared_manifest(manifest: dict, contracts: dict, contents: dict | None = None,
                          jar_declaration: bytes | str | None = None) -> dict:
    """Fixture comparison in the issuer's version → Schema → semantics → bytes order."""
    version = manifest.get("schema_version") if isinstance(manifest, dict) else None
    if type(version) is not int or version not in (1, 2):
        return {"valid": False, "reason": "unsupported_schema_version", "stage": "version"}
    validator = schema_validator(contracts["schema" if version == 2 else "legacy_v1_schema"])
    if not validator.is_valid(manifest):
        return {"valid": False, "reason": "schema_invalid", "stage": "schema"}
    result = check_manifest_semantics(manifest)
    if not result["valid"]:
        return result
    if contents is not None:
        return check_manifest_contents(manifest, contents, jar_declaration)
    return result
