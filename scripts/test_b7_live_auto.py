#!/usr/bin/env python3
"""Deterministic tests for b7_live_auto.py; no network or Paper server required."""

from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

import b7_live_auto as runner


class FakeClock:
    def __init__(self):
        self.value = 0.0

    def monotonic(self):
        return self.value

    def sleep(self, seconds):
        self.value += seconds


class B7LiveAutoDeterministicTest(unittest.TestCase):
    def test_direction_value_enforces_six_fractional_digits_and_norm(self):
        self.assertEqual([0.267261, 0.534522, 0.801784],
                         runner.direction_value("valid", [0.267261, 0.534522, 0.801784]))
        with self.assertRaisesRegex(AssertionError, "more than 6"):
            runner.direction_value("precision", [0.2672612, 0.534522, 0.801784])
        with self.assertRaisesRegex(AssertionError, "outside tolerance"):
            runner.direction_value("norm", [1, 1, 1])
        with self.assertRaisesRegex(AssertionError, "non-finite"):
            runner.direction_value("finite", [float("inf"), 0, 0])

    def test_java_properties_parser_unescapes_namespaced_values(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "observation.properties"
            path.write_text(
                "# generated\nstatus=complete\n"
                "later.block.rod=minecraft\\:lightning_rod\n"
                "event.exact_count=1\n",
                encoding="iso-8859-1",
            )
            self.assertEqual({
                "status": "complete",
                "later.block.rod": "minecraft:lightning_rod",
                "event.exact_count": "1",
            }, runner.read_properties(path))

    def test_probe_control_is_atomic_and_contains_no_identity_secret(self):
        with tempfile.TemporaryDirectory() as directory:
            clock = FakeClock()
            controller = runner.ProbeController(
                directory, timeout=0.2, monotonic=clock.monotonic, sleep=clock.sleep)
            original_sleep = controller.sleep

            def complete_after_write(seconds):
                control = runner.read_properties(controller.control)
                controller.observation.write_text(
                    f"sequence={control['sequence']}\nstatus=complete\n",
                    encoding="iso-8859-1",
                )
                original_sleep(seconds)

            controller.sleep = complete_after_write
            observed = controller.issue("snapshot", run_id="deterministic",
                                        world="minecraft:overworld",
                                        target_x=1, target_y=2, target_z=3)
            control_text = controller.control.read_text(encoding="utf-8")
            self.assertEqual("complete", observed["status"])
            self.assertIn("target.x=1", control_text)
            self.assertNotIn("token", control_text.lower())
            self.assertNotIn("uuid", control_text.lower())

    def test_lightning_request_is_sent_exactly_once_without_retry(self):
        class Rpc:
            def __init__(self):
                self.calls = []

            def call(self, method, params):
                self.calls.append((method, params))
                return {"jsonrpc": "2.0", "id": 1, "result": None}

        class Probe:
            def __init__(self):
                self.sequence = 41

            def issue(self, *_args, **_kwargs):
                return {"sequence": "42", "status": "ready"}

            def wait_complete(self, sequence):
                assert sequence == "42"
                return {
                    "event.exact_target": "true",
                    "event.exact_count": "1",
                    "event.cause": "CUSTOM",
                    "event.cancelled.final": "false",
                    "status": "complete",
                }

        rpc = Rpc()
        args = SimpleNamespace(origin=(100, 64, 100), dimension="minecraft:overworld",
                               later_ticks=40)
        runner.arm_and_strike(rpc, Probe(), args, "once", (1.5, 2, 3.5), False, "", "")
        self.assertEqual([("world.strikeLightning", [1.5, 2, 3.5])], rpc.calls)

    def test_cancelled_and_non_cancelled_are_distinct_pass_conditions(self):
        base = {
            "event.exact_target": "true", "event.exact_count": "1",
            "event.cause": "CUSTOM", "status": "complete",
        }

        class Rpc:
            def call(self, _method, _params):
                return {"jsonrpc": "2.0", "id": 1, "result": None}

        class Probe:
            def __init__(self, final):
                self.final = final

            def issue(self, *_args, **_kwargs):
                return {"sequence": "1", "status": "ready"}

            def wait_complete(self, _sequence):
                return dict(base, **{"event.cancelled.final": self.final})

        args = SimpleNamespace(origin=(0, 0, 0), dimension="minecraft:overworld", later_ticks=2)
        runner.arm_and_strike(Rpc(), Probe("false"), args, "noncancel", (1, 2, 3), False, "", "")
        runner.arm_and_strike(Rpc(), Probe("true"), args, "cancel", (1, 2, 3), True, "", "")
        with self.assertRaisesRegex(AssertionError, "cancellation mismatch"):
            runner.arm_and_strike(Rpc(), Probe("true"), args, "wrong", (1, 2, 3), False, "", "")

    def test_probe_sources_are_outside_product_source_set(self):
        root = Path(__file__).resolve().parents[1]
        product_plugin = (root / "src/main/resources/plugin.yml").read_text(encoding="utf-8")
        probe_plugin = (root / "live/b7/probe/src/main/resources/paper-plugin.yml").read_text(
            encoding="utf-8")
        self.assertNotIn("B7LiveProbe", product_plugin)
        self.assertIn("B7LiveProbe", probe_plugin)
        self.assertFalse((root / "src/main/java/club/code2create/mcremote/liveprobe").exists())


if __name__ == "__main__":
    unittest.main()
