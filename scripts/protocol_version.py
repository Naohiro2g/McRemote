"""検証スクリプトの既定 protocol をプラグインの定数から読む。

リポジトリの checkout 内で使う。server の hello に追従させず、検証する
source の版を要求する。別の版を試す場合は各 runner の --protocol を使う。
"""

from pathlib import Path
import re


PROTOCOL_SOURCE = (
    Path(__file__).resolve().parents[1]
    / "src/main/java/club/code2create/mcremote/ProtocolInfo.java"
)


def read_protocol(source: Path = PROTOCOL_SOURCE) -> str:
    try:
        text = source.read_text(encoding="utf-8")
    except OSError as error:
        raise RuntimeError(f"cannot read protocol source: {source}") from error
    declarations = re.findall(
        r'^\s*public static final String PROTOCOL\s*=\s*"([^"\n]*)"\s*;',
        text, re.MULTILINE,
    )
    if len(declarations) != 1 or re.fullmatch(r"\d+\.\d+\.\d+", declarations[0]) is None:
        raise RuntimeError(f"expected one clean protocol semver declaration in {source}")
    return declarations[0]


PROTOCOL = read_protocol()
