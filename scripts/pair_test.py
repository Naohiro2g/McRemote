#!/usr/bin/env python3
"""McRemote pairing, catalog, and credential lifecycle smoke test.

Python standard library only. The normal path verifies:

    auth.pairBegin -> /mcremote pair -> auth.pairPoll -> hello
        -> catalog.get -> auth.listCredentials (long-lived only)

Long-lived credential restart test:

    python3 scripts/pair_test.py --token-type long_lived --device test-pc \
        --save-token-file /tmp/mcremote-restart.token
    # restart the server
    python3 scripts/pair_test.py --token-file /tmp/mcremote-restart.token
    python3 scripts/pair_test.py --token-file /tmp/mcremote-restart.token \
        --logout-after-test
    python3 scripts/pair_test.py --token-file /tmp/mcremote-restart.token \
        --expect-token-revoked

Token files are explicit test artifacts. They must be outside this Git worktree,
must be regular files owned by the current user, and must have mode 0600.
The raw token is never printed.
"""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import socket
import stat
import subprocess
import sys
import time


PROTOCOL = "21.0.0"
REPO_ROOT = Path(__file__).resolve().parent.parent


def external_token_path(raw_path: str) -> Path:
    """Resolve a token path and reject anything inside this Git worktree."""
    path = Path(raw_path).expanduser()
    if not path.is_absolute():
        path = Path.cwd() / path
    path = path.parent.resolve() / path.name
    try:
        path.relative_to(REPO_ROOT)
    except ValueError:
        return path
    raise ValueError(f"token file must be outside the Git worktree: {path}")


def validate_token(token: str) -> str:
    """Validate the opaque token envelope and return its effective token type."""
    if token.startswith("mcrl_"):
        token_type = "long_lived"
    elif token.startswith("mcrs_"):
        token_type = "session"
    else:
        raise ValueError("token file has an unknown token prefix")
    payload = token[5:]
    allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    if not payload or not token.isascii() or any(c not in allowed for c in payload):
        raise ValueError("token file contains an invalid token")
    return token_type


def save_token_file(raw_path: str, token: str) -> Path:
    """Create a Git-external, owner-only token file without overwriting."""
    path = external_token_path(raw_path)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="ascii", newline="\n") as stream:
            stream.write(token + "\n")
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        try:
            path.unlink()
        except OSError:
            pass
        raise
    return path


def load_token_file(raw_path: str) -> tuple[str, str, Path]:
    """Read an owner-only regular token file without following a final symlink."""
    path = external_token_path(raw_path)
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    with os.fdopen(fd, "r", encoding="ascii") as stream:
        metadata = os.fstat(stream.fileno())
        if not stat.S_ISREG(metadata.st_mode):
            raise ValueError(f"token file is not a regular file: {path}")
        if hasattr(os, "getuid") and metadata.st_uid != os.getuid():
            raise ValueError(f"token file is not owned by the current user: {path}")
        if stat.S_IMODE(metadata.st_mode) != 0o600:
            raise ValueError(f"token file permissions must be 0600: {path}")
        raw = stream.read(4097)
    if len(raw) > 4096:
        raise ValueError(f"token file is unexpectedly large: {path}")
    token = raw[:-1] if raw.endswith("\n") else raw
    if raw not in (token, token + "\n") or not token or any(c.isspace() for c in token):
        raise ValueError(f"token file must contain exactly one token: {path}")
    return token, validate_token(token), path


def copy_to_clipboard(text: str) -> None:
    """Copy pairing command, best-effort: Wayland, X11, then OSC 52."""
    for cmd in (["wl-copy"], ["xclip", "-selection", "clipboard"],
                ["xsel", "--clipboard", "--input"]):
        if shutil.which(cmd[0]):
            try:
                subprocess.run(cmd, input=text.encode("utf-8"), check=True)
                print(f"  (clipboard: {cmd[0]} でコピー / copied)")
                return
            except (OSError, subprocess.SubprocessError):
                continue
    encoded = base64.b64encode(text.encode("utf-8")).decode("ascii")
    sys.stdout.write(f"\033]52;c;{encoded}\a")
    sys.stdout.flush()
    print("  (clipboard: OSC 52 を試行・端末依存 / OSC 52 attempted, terminal-dependent)")


class RpcClient:
    def __init__(self, host: str, port: int, timeout: float):
        self._socket = socket.create_connection((host, port), timeout=timeout)
        self._socket.settimeout(timeout)
        self._reader = self._socket.makefile("rb")
        self._next_id = 0

    def close(self) -> None:
        self._reader.close()
        self._socket.close()

    def expect_closed(self, timeout: float = 5.0) -> bool:
        """Wait for server EOF when the protocol requires this session to close."""
        self._socket.settimeout(timeout)
        try:
            return self._reader.readline() == b""
        except socket.timeout:
            return False

    def call(self, method: str, params) -> dict:
        self._next_id += 1
        request_id = self._next_id
        message = {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}
        wire = json.dumps(message, separators=(",", ":")) + "\n"
        self._socket.sendall(wire.encode("utf-8"))
        raw = self._reader.readline()
        if not raw:
            raise RuntimeError("connection closed by server")
        response = json.loads(raw.decode("utf-8").rstrip("\r\n"))
        if response.get("id") != request_id:
            raise RuntimeError(
                f"{method} -> id mismatch: want {request_id}, got {response.get('id')}"
            )
        return response


def acquire_token(client: RpcClient, args) -> tuple[str, str, Path | None, str]:
    if args.token_file:
        token, token_type, token_path = load_token_file(args.token_file)
        print(f"[token.file] <- loaded type={token_type} path={token_path}")
        return token, token_type, token_path, "token file"

    begin_params = {
        "token_type": args.token_type,
        "client": {"name": "pair_test.py", "version": "0", "locale": "ja"},
    }
    if args.device:
        begin_params["device"] = args.device
    begin = client.call("auth.pairBegin", begin_params)
    if "error" in begin:
        raise AssertionError(f"auth.pairBegin -> error {begin['error']}")
    result = begin["result"]
    pairing_id = result["pairing_id"]
    pair_code = result["pair_code"]
    expires_in = result["expires_in"]
    grouped = f"{pair_code[:3]}-{pair_code[3:]}"
    command = f"/mcremote pair {grouped}"
    print(f"[pairBegin] pairing_id={pairing_id} expires_in={expires_in}s")
    print()
    print("  ── ペアリング / Pairing ─────────────────────────────")
    print("    チャットに貼付 / paste into chat:")
    print(f"      {command}")
    print("    （区切り不要・半角数字 / ASCII digits, separators optional）")
    print(f"    コピー用 / copy: {command}")
    print("  ────────────────────────────────────────────────────")
    if args.clipboard:
        copy_to_clipboard(command)
    print()

    deadline = time.monotonic() + expires_in
    token = None
    while time.monotonic() < deadline:
        poll = client.call("auth.pairPoll", {"pairing_id": pairing_id})
        if "error" in poll:
            reason = poll["error"].get("data", {}).get("reason")
            raise AssertionError(f"auth.pairPoll -> error reason={reason}")
        status = poll["result"].get("status")
        if status == "pending":
            print("[pairPoll]  pending ... (待機中)")
            time.sleep(args.poll_interval)
            continue
        if status == "ok":
            token = poll["result"]["token"]
            break
        raise AssertionError(f"unexpected pairPoll status: {status!r}")
    if token is None:
        raise AssertionError("timed out waiting for /mcremote pair (pair_code expired)")

    token_type = validate_token(token)
    print(f"[pairPoll]  ok -> type={token_type} ({len(token)} chars)")
    token_path = None
    if args.save_token_file:
        token_path = save_token_file(args.save_token_file, token)
        print(f"[token.file] -> saved mode=0600 path={token_path}")
    elif token_type == "long_lived" and not args.logout_after_test:
        print("WARN: raw token was not saved; this credential cannot be reused after exit")
    return token, token_type, token_path, "pair"


def verify_hello_and_catalog(client: RpcClient, token: str, protocol: str) -> None:
    hello = client.call("hello", {"protocol": protocol, "auth": {"token": token}})
    if "error" in hello:
        raise AssertionError(f"hello -> error {hello['error']}")
    info = hello["result"]
    print(f"[hello]     <- protocol={info.get('protocol')} mc_version={info.get('mc_version')}")
    world_constants = info.get("world_constants")
    if not isinstance(world_constants, dict) or "y_sea" not in world_constants:
        raise AssertionError(f"hello world_constants must contain y_sea: {info}")
    if "y_sea" in info:
        raise AssertionError(f"y_sea must not be top-level: {info}")
    catalog_hash = info.get("catalogHash")
    if not (isinstance(catalog_hash, str) and len(catalog_hash) == 64
            and all(c in "0123456789abcdef" for c in catalog_hash)):
        raise AssertionError(f"catalogHash must be SHA-256 hex for b3: {info}")
    print(
        f"[hello]     <- world_constants.y_sea={world_constants.get('y_sea')} "
        f"catalogHash={catalog_hash}"
    )
    print(f"[hello]     <- player={info.get('player')} permissions={info.get('permissions')}")
    if info.get("player") is None:
        print("WARN: hello に player が無い（token 未束縛）。")

    catalog_response = client.call("catalog.get", [])
    if "error" in catalog_response:
        raise AssertionError(f"catalog.get -> error {catalog_response['error']}")
    catalog = catalog_response["result"]
    body = {key: catalog.get(key) for key in ("block", "entity", "particle")}
    if not all(isinstance(body[key], dict) for key in body):
        raise AssertionError(f"catalog categories must be objects: {catalog}")
    canonical = json.dumps(
        body, sort_keys=True, separators=(",", ":"), ensure_ascii=True
    ).encode("utf-8")
    computed_hash = hashlib.sha256(canonical).hexdigest()
    if catalog.get("catalogHash") != catalog_hash or computed_hash != catalog_hash:
        raise AssertionError(
            "catalog hash mismatch "
            f"hello={catalog_hash} response={catalog.get('catalogHash')} computed={computed_hash}"
        )
    print(
        f"[catalog.get] <- blocks={len(body['block'])} entities={len(body['entity'])} "
        f"particles={len(body['particle'])} bytes={len(canonical)} hash=OK"
    )


def verify_long_lived(client: RpcClient, logout_after_test: bool) -> None:
    listed = client.call("auth.listCredentials", [])
    if "error" in listed:
        raise AssertionError(f"auth.listCredentials -> error {listed['error']}")
    credentials = listed["result"].get("credentials")
    current = [item for item in credentials or [] if item.get("current") is True]
    if len(current) != 1 or current[0].get("type") != "long_lived":
        raise AssertionError(f"expected exactly one current long_lived credential: {credentials}")
    credential_id = current[0].get("credential_id")
    print(
        f"[auth.listCredentials] <- active={len(credentials)} "
        f"current={credential_id} device={current[0].get('device')!r}"
    )
    if not logout_after_test:
        return
    logout = client.call("auth.logout", [])
    if "error" in logout or logout.get("result", {}).get("revoked") is not True:
        raise AssertionError(f"auth.logout -> {logout}")
    if logout["result"].get("credential_id") != credential_id:
        raise AssertionError(f"auth.logout credential_id mismatch: {logout}")
    print(f"[auth.logout] <- revoked=true id={credential_id}")


def parse_args():
    parser = argparse.ArgumentParser(description="McRemote pairing smoke test")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--protocol", default=PROTOCOL)
    parser.add_argument("--token-type", default="session", choices=["session", "long_lived"])
    parser.add_argument("--device", default=None, help="optional long-lived credential device label")
    parser.add_argument("--poll-interval", type=float, default=1.5, help="pairPoll interval")
    parser.add_argument("--timeout", type=float, default=10.0, help="socket read timeout")
    token_files = parser.add_mutually_exclusive_group()
    token_files.add_argument(
        "--save-token-file", metavar="PATH",
        help="save an issued token to a new Git-external 0600 file",
    )
    token_files.add_argument(
        "--token-file", metavar="PATH",
        help="load a saved token and skip pairing",
    )
    parser.add_argument(
        "--logout-after-test", action="store_true",
        help="revoke the current long-lived credential with auth.logout",
    )
    parser.add_argument(
        "--expect-token-revoked", action="store_true",
        help="expect token_revoked from --token-file, then delete that file",
    )
    parser.add_argument(
        "--clipboard", action="store_true",
        help="copy the pairing command to the clipboard (best-effort)",
    )
    args = parser.parse_args()
    if args.expect_token_revoked and not args.token_file:
        parser.error("--expect-token-revoked requires --token-file")
    if args.expect_token_revoked and args.logout_after_test:
        parser.error("--expect-token-revoked and --logout-after-test cannot be combined")
    if args.token_file and args.device:
        parser.error("--device only applies when issuing a new token")
    return args


def main() -> int:
    args = parse_args()
    client = None
    try:
        client = RpcClient(args.host, args.port, args.timeout)
        token, token_type, token_path, source = acquire_token(client, args)

        if args.expect_token_revoked:
            response = client.call("hello", {"protocol": args.protocol, "auth": {"token": token}})
            reason = response.get("error", {}).get("data", {}).get("reason")
            if reason != "token_revoked":
                raise AssertionError(f"expected token_revoked, got {response}")
            token_path.unlink()
            print("[hello]     <- token_revoked (expected)")
            print(f"[token.file] -> deleted path={token_path}")
            print()
            print("PASS: revoked credential rejected after reconnect; local token deleted")
            return 0

        verify_hello_and_catalog(client, token, args.protocol)
        if token_type == "long_lived":
            verify_long_lived(client, args.logout_after_test)
        elif args.logout_after_test:
            raise AssertionError("--logout-after-test requires a long-lived credential")

        print()
        print(f"PASS: {source} -> hello -> catalog.get + credential lifecycle verification")
        if args.logout_after_test and token_path:
            print("NEXT: verify token_revoked and delete the local token:")
            print(
                "  python3 scripts/pair_test.py --token-file "
                f"{shlex.quote(str(token_path))} --expect-token-revoked"
            )
        return 0
    except AssertionError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    except (OSError, RuntimeError, UnicodeError, ValueError,
            json.JSONDecodeError, KeyError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    finally:
        if client is not None:
            try:
                client.close()
            except OSError:
                pass


if __name__ == "__main__":
    sys.exit(main())
