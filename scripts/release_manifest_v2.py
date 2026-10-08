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


def load_json(path: Path) -> Any:
    def unique_keys(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON field: {key}")
            result[key] = value
        return result

    def invalid_constant(value):
        raise ValueError(f"non-JSON numeric constant: {value}")

    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_keys,
                      parse_constant=invalid_constant)


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
    keys, role_variants, jars = set(), {}, []
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
        variant = "os" in artifact
        role = artifact["role"]
        if role in role_variants and role_variants[role] != variant:
            raise ValueError(f"mixed variant and unqualified artifacts for role {role}")
        role_variants[role] = variant
        key = (role, artifact.get("os"), artifact.get("arch"))
        if key in keys:
            raise ValueError(f"duplicate artifact key: {key}")
        keys.add(key)
        if role == "jar":
            jars.append(artifact)
    compatibility = manifest.get("minecraft_compatibility")
    if not jars and "minecraft_compatibility" not in manifest:
        return
    if len(jars) != 1 or jars[0]["kind"] != "https-file" or "os" in jars[0]:
        raise ValueError("minecraft_compatibility requires exactly one unqualified https-file jar")
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
    seen = set()
    for check in checks:
        fields(check, (*VERIFICATION_FIELDS, "record"), label="verification")
        string(check["minecraft_version"], "minecraft_version")
        integer(check["paper_build"], 1, "paper_build")
        string(check["java_version"], "java_version")
        hex_string(check["server_sha256"], 64, "server_sha256")
        hex_string(check["jar_sha256"], 64, "jar_sha256")
        if check["result"] != "PASS":
            raise ValueError("verification result must be PASS")
        if check["jar_sha256"] != jars[0]["sha256"]:
            raise ValueError("verification jar_sha256 differs from jar artifact")
        if check["minecraft_version"] in seen:
            raise ValueError("duplicate Minecraft verification")
        seen.add(check["minecraft_version"])
        fields(check["record"], ("file", "sha256"), label="record")
        basename(check["record"]["file"], "record.file")
        hex_string(check["record"]["sha256"], 64, "record.sha256")
        if any(a.get("file") == check["record"]["file"] for a in manifest["artifacts"]):
            raise ValueError("verification records must not be listed in artifacts")
    if seen != set(declaration["minecraft_versions"]):
        raise ValueError("declaration and verification version sets differ")


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


def validate_locked_schema(manifest: dict, lock_path: Path) -> None:
    """Use only locally pinned bytes; never retrieve main or remote $refs."""
    if not lock_path.is_file():
        raise ValueError("fixed tooling release-manifest schema lock is missing; publication is not ready")
    lock = load_json(lock_path)
    fields(lock, ("repository", "source_commit", "schema"), ("fixtures",), "schema lock")
    if lock["repository"] != "Naohiro2g/minecraft-remote-tooling":
        raise ValueError("schema lock must reference minecraft-remote-tooling")
    hex_string(lock["source_commit"], 40, "schema lock source_commit")
    entry = lock["schema"]
    fields(entry, ("path", "local_path", "bytes", "sha256"), label="locked schema")
    if entry["path"] != "schemas/release-manifest-v2.schema.json":
        raise ValueError("unexpected upstream schema path")
    integer(entry["bytes"], 1, "schema bytes")
    hex_string(entry["sha256"], 64, "schema sha256")
    string(entry["local_path"], "schema local_path")
    local = (lock_path.parent / entry["local_path"]).resolve()
    if not local.is_relative_to(lock_path.parent.resolve()):
        raise ValueError("local schema path escapes lock directory")
    raw = local.read_bytes()
    if len(raw) != entry["bytes"] or hashlib.sha256(raw).hexdigest() != entry["sha256"]:
        raise ValueError("fixed schema bytes/digest differ from lock")
    schema = load_json(local)
    if not isinstance(schema, dict) or schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
        raise ValueError("fixed schema must be Draft 2020-12")
    try:
        from jsonschema import Draft202012Validator
        from referencing import Registry
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema, registry=Registry()).validate(manifest)
    except ImportError as error:
        raise ValueError("install scripts/requirements-release.txt to validate the fixed schema") from error
    except Exception as error:
        raise ValueError(f"fixed JSON Schema validation failed: {error}") from error
