#!/usr/bin/env python3
"""Update only the generated supported-Minecraft section of a Release body."""

import argparse
from pathlib import Path

BEGIN = "<!-- BEGIN: MCREMOTE TARGETS -->"
END = "<!-- END: MCREMOTE TARGETS -->"


def update_body(body: str, section: str) -> str:
    if section.count(BEGIN) != 1 or section.count(END) != 1 or section.index(BEGIN) > section.index(END):
        raise ValueError("invalid generated Minecraft section")
    if BEGIN not in body and END not in body:
        return body.rstrip() + ("\n\n" if body.strip() else "") + section
    if body.count(BEGIN) != 1 or body.count(END) != 1 or body.index(BEGIN) > body.index(END):
        raise ValueError("duplicated or incomplete Minecraft markers in Release body")
    start, end = body.index(BEGIN), body.index(END) + len(END)
    return body[:start] + section.rstrip("\n") + body[end:]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--body", type=Path, required=True)
    parser.add_argument("--section", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        args.output.write_text(update_body(args.body.read_text(encoding="utf-8"),
                                          args.section.read_text(encoding="utf-8")), encoding="utf-8")
    except (OSError, ValueError) as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
