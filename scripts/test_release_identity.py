import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "release_identity", Path(__file__).with_name("release_identity.py"))
RELEASE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = RELEASE
SPEC.loader.exec_module(RELEASE)


class ReleaseIdentityTest(unittest.TestCase):
    def test_published_titles_follow_the_rule(self):
        # The b7 titles the coordinator corrected by hand (2026-09-27-03).
        for tag, title in (
                ("v1.21.11-2301.0.0b7", "McRemote 1.21.11 / 2301.0.0b7"),
                ("v1.21.11-2301.0.0b7.post2", "McRemote 1.21.11 / 2301.0.0b7.post2"),
                ("v1.21.11-2320.0.0b8", "McRemote 1.21.11 / 2320.0.0b8"),
                ("v26.3-2400.0.0rc1", "McRemote 26.3 / 2400.0.0rc1"),
                ("v26.3-2400.0.0", "McRemote 26.3 / 2400.0.0")):
            self.assertEqual(title, RELEASE.parse_tag(tag).title, tag)

    def test_jar_name_and_parts(self):
        identity = RELEASE.parse_tag("v1.21.11-2301.0.0b7.post2")
        self.assertEqual("1.21.11", identity.mc_target)
        self.assertEqual("2301.0.0b7.post2", identity.version)
        self.assertEqual("mc-remote-1.21.11-2301.0.0b7.post2.jar", identity.jar)

    def test_prerelease_is_explicit_for_a_b_rc_and_their_post_releases(self):
        for tag in ("v1.21.11-2320.0.0b8", "v1.21.11-2301.0.0b7.post2",
                    "v26.3-2400.0.0rc1", "v26.3-2400.0.0a1"):
            self.assertTrue(RELEASE.parse_tag(tag).prerelease, tag)
        for tag in ("v26.3-2400.0.0", "v26.3-2400.0.0.post1"):
            self.assertFalse(RELEASE.parse_tag(tag).prerelease, tag)

    def test_rejects_tags_outside_the_rule(self):
        for tag in ("1.21.11-2320.0.0b8", "v2320.0.0b8", "v1.21.11-2320.0.0-beta.8",
                    "v1.21.11-2301.0.0b7-post1", "v1.21.11_2320.0.0b8", "v1.21.11-2320.0.0b8 ",
                    "McRemote 1.21.11 / 2320.0.0b8", ""):
            with self.assertRaises(ValueError, msg=tag):
                RELEASE.parse_tag(tag)

    def test_cli_writes_github_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "out"
            argv = sys.argv
            sys.argv = ["release_identity.py", "v1.21.11-2320.0.0b8", "--github-output", str(output)]
            try:
                self.assertEqual(0, RELEASE.main())
            finally:
                sys.argv = argv
            lines = output.read_text(encoding="utf-8").splitlines()
        self.assertIn("title=McRemote 1.21.11 / 2320.0.0b8", lines)
        self.assertIn("jar=mc-remote-1.21.11-2320.0.0b8.jar", lines)
        self.assertIn("prerelease=true", lines)


if __name__ == "__main__":
    unittest.main()
