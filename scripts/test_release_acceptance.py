"""Synthetic schema tests only; these are NOT product acceptance evidence."""
import copy
import unittest
from verify_release_acceptance import REQUIRED_GATES, REQUIRED_METRICS, validate_acceptance


def synthetic_record():
    return {
        "version": "0.4.0", "candidate_sha": "a" * 40, "device_kind": "physical",
        "gates": {name: {"status": "passed", "evidence": "synthetic fixture, not an actual run"} for name in REQUIRED_GATES},
        "performance": {name: {"before": 100, "after": 95, "evidence": "synthetic fixture"} for name in REQUIRED_METRICS},
    }


class ReleaseAcceptanceTest(unittest.TestCase):
    def test_missing_evidence_fails_closed(self):
        with self.assertRaises(ValueError):
            validate_acceptance({}, "0.4.0")

    def test_every_required_gate_is_mandatory(self):
        for name in REQUIRED_GATES:
            with self.subTest(gate=name):
                record = synthetic_record()
                del record["gates"][name]
                with self.assertRaises(ValueError):
                    validate_acceptance(record, "0.4.0")

    def test_pending_or_failed_cannot_pass(self):
        for status in ("pending", "failed", "skipped", "queued"):
            record = synthetic_record()
            record["gates"][REQUIRED_GATES[0]]["status"] = status
            with self.assertRaises(ValueError):
                validate_acceptance(record, "0.4.0")

    def test_emulator_is_not_physical_acceptance(self):
        record = synthetic_record()
        record["device_kind"] = "emulator"
        with self.assertRaises(ValueError):
            validate_acceptance(record, "0.4.0")

    def test_version_must_match(self):
        with self.assertRaises(ValueError):
            validate_acceptance(synthetic_record(), "0.4.1")

    def test_source_sha_is_required(self):
        record = synthetic_record()
        record["candidate_sha"] = "latest"
        with self.assertRaises(ValueError):
            validate_acceptance(record, "0.4.0")

    def test_each_metric_requires_real_numeric_pair_and_evidence(self):
        for bad in (None, True, 0, -1, float("nan"), float("inf"), "100"):
            record = synthetic_record()
            record["performance"][REQUIRED_METRICS[0]]["after"] = bad
            with self.assertRaises(ValueError):
                validate_acceptance(record, "0.4.0")

    def test_regression_is_blocked(self):
        record = synthetic_record()
        record["performance"][REQUIRED_METRICS[0]]["after"] = 106
        with self.assertRaises(ValueError):
            validate_acceptance(record, "0.4.0")

    def test_missing_evidence_link_is_blocked(self):
        record = synthetic_record()
        record["gates"][REQUIRED_GATES[0]]["evidence"] = " "
        with self.assertRaises(ValueError):
            validate_acceptance(record, "0.4.0")

    def test_complete_synthetic_schema_is_valid_without_mutation(self):
        record = synthetic_record()
        before = copy.deepcopy(record)
        self.assertEqual("a" * 40, validate_acceptance(record, "0.4.0"))
        self.assertEqual(before, record)


if __name__ == "__main__":
    unittest.main()
