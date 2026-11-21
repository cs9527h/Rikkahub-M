"""Synthetic process-result contracts only; never substitute these for Android test evidence."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parent / "run-connected-verification.sh"


class ConnectedVerificationWrapperTest(unittest.TestCase):
    def run_fixture(self, gradle_status, log_content, adb_status=0):
        with tempfile.TemporaryDirectory(prefix="connected-wrapper-contract-") as directory:
            root = Path(directory)
            gradle = root / "gradlew"
            gradle.write_text('#!/bin/sh\nexit "$SYNTHETIC_GRADLE_STATUS"\n')
            gradle.chmod(0o700)
            adb = root / "adb"
            adb.write_text('#!/bin/sh\nprintf "%s\\n" "$SYNTHETIC_LOG"\nexit "$SYNTHETIC_ADB_STATUS"\n')
            adb.chmod(0o700)
            env = {
                **os.environ, "PATH": str(root) + os.pathsep + os.environ["PATH"],
                "SYNTHETIC_GRADLE_STATUS": str(gradle_status), "SYNTHETIC_LOG": log_content,
                "SYNTHETIC_ADB_STATUS": str(adb_status),
            }
            result = subprocess.run(["bash", str(SCRIPT)], cwd=root, env=env, capture_output=True, text=True, timeout=10)
            evidence = (root / "evidence/long-chat-database-perf.log").read_text()
            return result, evidence

    def test_gradle_failure_remains_failure_even_when_metrics_exist(self):
        result, _ = self.run_fixture(7, "LONG_CHAT_DB_PERF synthetic")
        self.assertEqual(7, result.returncode)

    def test_gradle_failure_is_preserved_when_evidence_collection_also_fails(self):
        result, _ = self.run_fixture(7, "", 2)
        self.assertEqual(7, result.returncode)

    def test_missing_metrics_cannot_turn_green(self):
        result, _ = self.run_fixture(0, "")
        self.assertEqual(1, result.returncode)

    def test_failed_evidence_collection_cannot_turn_green(self):
        result, _ = self.run_fixture(0, "LONG_CHAT_DB_PERF synthetic", 2)
        self.assertEqual(1, result.returncode)

    def test_success_requires_both_actual_gradle_status_and_metrics(self):
        result, evidence = self.run_fixture(0, "LONG_CHAT_DB_PERF synthetic")
        self.assertEqual(0, result.returncode)
        self.assertIn("LONG_CHAT_DB_PERF synthetic", evidence)


if __name__ == "__main__":
    unittest.main()
