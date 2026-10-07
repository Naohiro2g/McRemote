#!/usr/bin/env python3
"""Derive McRemote identity. b10+ uses the pre-build target declaration.
Legacy MC-prefixed tags retain their published identity.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

TAG_PATTERN = re.compile(
    r"v(?P<mc_target>\d+\.\d+(?:\.\d+)?)"
    r"-(?P<version>\d+\.\d+\.\d+(?:(?P<pre>a|b|rc)\d+)?(?:\.post\d+)?)"
)


NEW_TAG_PATTERN = re.compile(r"v(?P<version>\d+\.\d+\.\d+(?:(?P<pre>a|b|rc)\d+)?(?:\.post\d+)?)")


def load_targets(path: Path) -> tuple[str, ...]:
    data = json.loads(path.read_text(encoding="utf-8"))
    versions = data.get("minecraft_versions")
    if (data.get("schema") != "mc-remote.minecraft-targets" or data.get("schema_version") != 1
            or not isinstance(versions, list) or not versions
            or any(not isinstance(v, str) or re.fullmatch(r"\d+\.\d+(?:\.\d+)?", v) is None for v in versions)
            or len(set(versions)) != len(versions)):
        raise ValueError("invalid Minecraft target declaration")
    return tuple(versions)


@dataclass(frozen=True)
class ReleaseIdentity:
    tag: str
    mc_target: str
    version: str
    prerelease: bool
    targets: tuple[str, ...] = ()

    @property
    def title(self) -> str:
        if self.targets:
            return f"McRemote {self.version} (Minecraft {', '.join(self.targets)})"
        return f"McRemote {self.mc_target} / {self.version}"

    @property
    def jar(self) -> str:
        return f"mc-remote-{self.version}.jar" if self.targets else f"mc-remote-{self.mc_target}-{self.version}.jar"


def parse_tag(tag: str, targets: tuple[str, ...] = ()) -> ReleaseIdentity:
    modern = NEW_TAG_PATTERN.fullmatch(tag)
    if modern:
        if not targets or len(set(targets)) != len(targets) or any(re.fullmatch(r"\d+\.\d+(?:\.\d+)?", t) is None for t in targets):
            raise ValueError("MC-free tag requires a valid pre-build target declaration")
        return ReleaseIdentity(tag, "", modern.group("version"), modern.group("pre") is not None, tuple(targets))
    match = TAG_PATTERN.fullmatch(tag)
    if match is None:
        raise ValueError(
            f"release tag must be v<mc-target>-<mc-remote-version>, got {tag!r}")
    return ReleaseIdentity(
        tag=tag,
        mc_target=match.group("mc_target"),
        version=match.group("version"),
        prerelease=match.group("pre") is not None,
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    parser.add_argument("--targets", type=Path, default=Path(__file__).resolve().parents[1] / "release/minecraft-targets.json")
    parser.add_argument("--body-output", type=Path, help="write the generated supported Minecraft section")
    parser.add_argument(
        "--github-output", type=Path,
        help="append tag/title/mc_target/version/jar/prerelease to this GITHUB_OUTPUT file")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        identity = parse_tag(args.tag, load_targets(args.targets) if NEW_TAG_PATTERN.fullmatch(args.tag) else ())
    except (ValueError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    lines = [
        f"tag={identity.tag}",
        f"title={identity.title}",
        f"mc_target={identity.mc_target}",
        f"version={identity.version}",
        f"jar={identity.jar}",
        f"prerelease={'true' if identity.prerelease else 'false'}",
    ]
    if args.body_output is not None:
        versions = identity.targets or (identity.mc_target,)
        args.body_output.write_text("<!-- BEGIN: MCREMOTE TARGETS -->\n対応Minecraft: " + ", ".join(versions) + "\n<!-- END: MCREMOTE TARGETS -->\n", encoding="utf-8")
    if args.github_output is not None:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
