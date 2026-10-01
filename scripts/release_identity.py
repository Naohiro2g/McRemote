#!/usr/bin/env python3
"""Derive the McRemote release identity from a release tag.

The tag is the only input (knowledge DECISIONS 2026-09-27-03, versioning-design §10.12.1):

    tag    v<mc-target>-<mc-remote-version>      v1.21.11-2320.0.0b8
    title  McRemote <mc-target> / <mc-remote-version>   McRemote 1.21.11 / 2320.0.0b8
    jar    mc-remote-<mc-target>-<mc-remote-version>.jar

A pre-release version (aN / bN / rcN, including its .postN) must be published as a GitHub
pre-release explicitly; GitHub does not infer it from the version (versioning-design §10.12).
"""
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

TAG_PATTERN = re.compile(
    r"v(?P<mc_target>\d+\.\d+(?:\.\d+)?)"
    r"-(?P<version>\d+\.\d+\.\d+(?:(?P<pre>a|b|rc)\d+)?(?:\.post\d+)?)"
)


@dataclass(frozen=True)
class ReleaseIdentity:
    tag: str
    mc_target: str
    version: str
    prerelease: bool

    @property
    def title(self) -> str:
        return f"McRemote {self.mc_target} / {self.version}"

    @property
    def jar(self) -> str:
        return f"mc-remote-{self.mc_target}-{self.version}.jar"


def parse_tag(tag: str) -> ReleaseIdentity:
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
    parser.add_argument(
        "--github-output", type=Path,
        help="append tag/title/mc_target/version/jar/prerelease to this GITHUB_OUTPUT file")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        identity = parse_tag(args.tag)
    except ValueError as error:
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
    if args.github_output is not None:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
