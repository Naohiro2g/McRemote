"""版の読み取りと runner の検証対象指定を、接続なしで確認する。"""

import contextlib
import importlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import live_auto
import live_human
import protocol_version
import sign_live_auto


class ProtocolVersionTest(unittest.TestCase):
    def test_reads_changed_source_and_rejects_missing_ambiguous_or_package_version(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "ProtocolInfo.java"
            with self.assertRaisesRegex(RuntimeError, "cannot read protocol source"):
                protocol_version.read_protocol(source)
            for version in ("23.0.0", "24.1.2"):
                declaration = f'    public static final String PROTOCOL = "{version}";\n'
                source.write_text(declaration, encoding="utf-8")
                self.assertEqual(version, protocol_version.read_protocol(source))
            for text in ("", declaration * 2,
                         'public static final String PROTOCOL = "2320.0.0b9";'):
                source.write_text(text, encoding="utf-8")
                with self.assertRaisesRegex(RuntimeError, "one clean protocol semver"):
                    protocol_version.read_protocol(source)

    def test_runner_defaults_follow_source(self):
        expected = protocol_version.read_protocol()
        for name in ("smoke_test", "player_test", "pair_test", "hello_auth_test",
                     "session_limit_test", "credential_multi_session_test",
                     "live_auto", "live_human", "sign_live_auto"):
            with self.subTest(runner=name):
                self.assertEqual(expected, importlib.import_module(name).PROTOCOL)

    def test_live_human_sends_explicit_protocol_override(self):
        rpc = mock.Mock()
        rpc.call.return_value = {"result": {
            "mc_version": "1.21.11", "player": "test-player",
            "dimension": "minecraft:overworld",
        }}
        live_human.hello(rpc, "test-token", "1.21.11", "23.0.0")
        self.assertEqual("23.0.0", rpc.call.call_args.args[1]["protocol"])

    def test_sign_requires_explicit_minecraft_target_before_connecting(self):
        with mock.patch("sys.argv", ["sign_live_auto.py"]), \
                mock.patch.object(sign_live_auto, "connect") as connect, \
                contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as error:
                sign_live_auto.main()
        self.assertEqual(2, error.exception.code)
        connect.assert_not_called()

    def test_sign_checks_target_before_mutating_world_and_accepts_protocol_override(self):
        for reported_mc, requested_protocol, expected_status in (
                ("26.3", None, 1),
                ("1.21.11", "23.0.0", 0)):
            argv = ["sign_live_auto.py", "--expect-mc", "1.21.11"]
            protocol = requested_protocol or protocol_version.PROTOCOL
            if requested_protocol:
                argv.extend(["--protocol", requested_protocol])
            rpc = mock.Mock()

            def call(method, params):
                if method == "hello":
                    return {"result": {
                        "protocol": protocol, "mc_version": reported_mc,
                        "dimension": "minecraft:overworld", "origin": [0, 0, 0],
                    }}
                if method in {"build.setDimension", "build.setOrigin"}:
                    return {"result": {"dimension": "minecraft:overworld", "origin": [0, 0, 0]}}
                if method == "world.getHeight":
                    return {"result": 64}
                if method == "world.getBlock":
                    return {"result": {"block_id": "minecraft:air", "state": {}}}
                self.fail(f"unexpected call: {method}")

            rpc.call.side_effect = call
            def connect(args, token):
                return live_auto.connect(args, token, rpc_factory=lambda *_: rpc)

            with self.subTest(reported_mc=reported_mc, protocol=protocol), \
                    mock.patch("sys.argv", argv), \
                    mock.patch.object(sign_live_auto, "connect", side_effect=connect), \
                    mock.patch.object(sign_live_auto, "verify_sign") as sign, \
                    mock.patch.object(sign_live_auto, "verify_get_sign_and_style") as style, \
                    mock.patch.object(sign_live_auto, "verify_update_sign_line") as line, \
                    contextlib.redirect_stdout(io.StringIO()), \
                    contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(expected_status, sign_live_auto.main())
            self.assertEqual(protocol, rpc.call.call_args_list[0].args[1]["protocol"])
            rpc.close.assert_called_once()
            for check in (sign, style, line):
                if expected_status:
                    check.assert_not_called()
                else:
                    check.assert_called_once_with(rpc, 64)
            if expected_status:
                self.assertEqual(["hello"], [call.args[0] for call in rpc.call.call_args_list])


if __name__ == "__main__":
    unittest.main()
