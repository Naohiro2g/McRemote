#!/usr/bin/env python3
"""Deterministic authentication fixtures for b5_live_auto.py."""

import importlib.util
import io
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest import mock


SCRIPT_PATH = Path(__file__).with_name("b5_live_auto.py")
SPEC = importlib.util.spec_from_file_location("b5_live_auto", SCRIPT_PATH)
LIVE_AUTO = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(LIVE_AUTO)

SESSION_TOKEN = "mcrs_" + "A" * 43


class TtyInput(io.StringIO):
    def isatty(self) -> bool:
        return True


class NonTtyInput(io.StringIO):
    def isatty(self) -> bool:
        return False


class PersistentRpc:
    def __init__(self):
        self.calls = []
        self.closed = False

    def call(self, method, params):
        self.calls.append((method, params))
        if method == "hello":
            return {"jsonrpc": "2.0", "id": 1,
                    "result": {"protocol": "22.0.0"}}
        return {"jsonrpc": "2.0", "id": len(self.calls), "result": None}

    def close(self):
        self.closed = True


class LiveAutoAuthenticationTest(unittest.TestCase):
    def setUp(self):
        self.args = SimpleNamespace(
            host="127.0.0.1", port=25575, timeout=10.0, protocol="22.0.0")

    def test_auth_required_pair_pending_token_authenticated_hello_sequence(self):
        calls = []
        responses = iter([
            {"jsonrpc": "2.0", "id": 1,
             "error": {"message": "auth_required",
                       "data": {"reason": "auth_required"}}},
            {"jsonrpc": "2.0", "id": 1, "result": {
                "pairing_id": "pairing-secret", "pair_code": "123456",
                "expires_in": 30,
            }},
            {"jsonrpc": "2.0", "id": 1, "result": {"status": "pending"}},
            {"jsonrpc": "2.0", "id": 1, "result": {
                "status": "ok", "token": SESSION_TOKEN,
            }},
        ])

        def fake_call_once(args, method, params):
            self.assertIs(args, self.args)
            calls.append((method, params))
            return next(responses)

        output = io.StringIO()
        token = LIVE_AUTO.acquire_interactive_token(
            self.args,
            input_stream=TtyInput(),
            output_stream=output,
            call_once_fn=fake_call_once,
            monotonic=lambda: 0.0,
            sleep=lambda _seconds: None,
        )
        persistent = PersistentRpc()
        connected = LIVE_AUTO.connect(
            self.args,
            token,
            rpc_factory=lambda _host, _port, _timeout: persistent,
        )

        self.assertEqual(SESSION_TOKEN, token)
        self.assertEqual("/mcremote pair 123-456\n", output.getvalue())
        self.assertNotIn(SESSION_TOKEN, output.getvalue())
        self.assertNotIn("pairing-secret", output.getvalue())
        self.assertEqual(
            ["hello", "auth.pairBegin", "auth.pairPoll", "auth.pairPoll"],
            [method for method, _params in calls],
        )
        self.assertEqual(1, sum(method == "auth.pairBegin" for method, _ in calls))
        poll_params = [params for method, params in calls if method == "auth.pairPoll"]
        self.assertEqual(
            [{"pairing_id": "pairing-secret"},
             {"pairing_id": "pairing-secret"}],
            poll_params,
        )
        self.assertEqual(
            ("hello", {"protocol": "22.0.0", "auth": {"token": SESSION_TOKEN}}),
            persistent.calls[0],
        )
        connected.close()

    def test_authenticated_connection_uses_in_memory_token_without_output(self):
        rpc = PersistentRpc()
        output = io.StringIO()
        with mock.patch("sys.stdout", output):
            connected = LIVE_AUTO.connect(
                self.args, SESSION_TOKEN,
                rpc_factory=lambda _host, _port, _timeout: rpc,
            )

        self.assertIs(rpc, connected)
        self.assertEqual(
            ("hello", {"protocol": "22.0.0", "auth": {"token": SESSION_TOKEN}}),
            rpc.calls[0],
        )
        self.assertNotIn(SESSION_TOKEN, output.getvalue())
        self.assertFalse(rpc.closed)
        connected.close()
        self.assertTrue(rpc.closed)

    def test_non_tty_fails_before_any_network_request(self):
        calls = []
        with self.assertRaisesRegex(RuntimeError, "interactive TTY"):
            LIVE_AUTO.acquire_interactive_token(
                self.args,
                input_stream=NonTtyInput(),
                output_stream=io.StringIO(),
                call_once_fn=lambda *args: calls.append(args),
            )
        self.assertEqual([], calls)

    def test_call_once_creates_and_closes_a_fresh_connection(self):
        created = []

        class OneShotRpc:
            def __init__(self):
                self.closed = False

            def call(self, method, params):
                return {"method": method, "params": params}

            def close(self):
                self.closed = True

        def factory(_host, _port, _timeout):
            rpc = OneShotRpc()
            created.append(rpc)
            return rpc

        for method in ("hello", "auth.pairBegin"):
            LIVE_AUTO.call_once(self.args, method, {}, rpc_factory=factory)

        self.assertEqual(2, len(created))
        self.assertIsNot(created[0], created[1])
        self.assertTrue(all(rpc.closed for rpc in created))


if __name__ == "__main__":
    unittest.main()
