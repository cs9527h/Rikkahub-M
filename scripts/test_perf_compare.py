"""Synthetic fixtures test the verifier, not application performance."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import perf_compare


class PerfCompareTest(unittest.TestCase):
    def test_missing_metric_is_a_failure_not_a_skip(self):
        self.assertTrue(perf_compare.compare("fixture", {"startup": 100}, {}, 10))

    def test_invalid_baseline_is_rejected(self):
        for bad in (0, -1, True, float("nan"), float("inf"), "100"):
            self.assertTrue(perf_compare.compare("fixture", {"startup": bad}, {"startup": 100}, 10))

    def test_regression_is_rejected(self):
        self.assertTrue(perf_compare.compare("fixture", {"startup": 100}, {"startup": 112.4}, 10))

    def test_improvement_is_accepted(self):
        self.assertFalse(perf_compare.compare("fixture", {"startup": 100}, {"startup": 90}, 10))

    def test_invalid_result_values_are_not_measurements(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "benchmark.json"
            for bad in (0, -1, True, float("nan"), float("inf"), "100"):
                path.write_text(json.dumps({"benchmarks": [{"name": "startup", "metrics": {"time": {"median": bad}}}]}))
                self.assertEqual({}, perf_compare.load_results([str(path)]))

    def test_missing_results_cannot_turn_green(self):
        with patch.object(perf_compare.glob, "glob", return_value=[]):
            self.assertEqual(1, perf_compare.main(["perf_compare.py"]))

    def test_corrupt_results_cannot_turn_green(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "benchmark.json"
            path.write_text("not JSON")
            self.assertEqual(1, perf_compare.main(["perf_compare.py", str(path)]))

    def test_emulator_success_does_not_claim_physical_acceptance(self):
        with tempfile.TemporaryDirectory() as temp:
            baseline = Path(temp) / "baseline.json"
            result = Path(temp) / "benchmark.json"
            baseline.write_text(json.dumps({"measured": False, "ciEmulator": {"metrics": {"startup.time": 100}}}))
            result.write_text(json.dumps({"benchmarks": [{"name": "startup", "metrics": {"time": {"median": 95}}}]}))
            output = io.StringIO()
            with patch.object(perf_compare, "BASELINE", baseline), contextlib.redirect_stdout(output):
                self.assertEqual(0, perf_compare.main(["perf_compare.py", str(result)]))
            self.assertIn("真机验收仍未完成", output.getvalue())


if __name__ == "__main__":
    unittest.main()
