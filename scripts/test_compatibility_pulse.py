import json
import unittest
from compatibility_pulse import sanitize


class CompatibilityPulseSanitizationTest(unittest.TestCase):
    def test_transcript_removes_bearer_token_and_player_identity(self):
        token = "mcrl_" + "a" * 43
        uuid = "12345678-1234-1234-1234-123456789abc"
        cleaned = sanitize({"params": {"auth": {"token": token}}, "response": {"player_uuid": uuid},
                            "pair_code": "284264", "version": "26.2"})
        encoded = json.dumps(cleaned)
        self.assertNotIn(token, encoded)
        self.assertNotIn(uuid, encoded)
        self.assertEqual("26.2", cleaned["version"])
        self.assertEqual("284264", cleaned["pair_code"])


if __name__ == "__main__":
    unittest.main()
