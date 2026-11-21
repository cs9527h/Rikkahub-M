"""Synthetic schema tests only; these are NOT product acceptance evidence."""
import copy
import unittest
from verify_release_acceptance import CI_PROVEN_GATES, REQUIRED_GATES, REQUIRED_METRICS, validate_acceptance


def synthetic_record():
    return {
        "version": "0.4.0", "candidate_sha": "a" * 40, "device_kind": "physical",
        "gates": {name: {"status": "passed", "evidence": "synthetic fixture, not an actual run"} for name in REQUIRED_GATES},
        "performance": {name: {"before": 100, "after": 95, "evidence": "synthetic fixture"} for name in REQUIRED_METRICS},
    }


def ci_only_record():
    return {
        "version": "0.4.4", "candidate_sha": "b" * 40, "device_kind": "ci-only",
        "physical_device_verified": False,
        "ci_run": {
            "run_id": 44910381716,
            "conclusion": "success",
            "url": "https://github.com/cs9527h/Rikkahub-M/actions/runs/44910381716",
        },
        "gates": {
            name: {"status": "passed", "evidence": "CI run detail " + name}
            for name in CI_PROVEN_GATES
        },
        "unverified_gates": ["physical_device_stability", "physical_device_chat_stream"],
        "performance": {"measured": False, "note": "未采集真机性能，未以模拟器数值代替"},
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


class CiOnlyAcceptanceTest(unittest.TestCase):
    def test_ci_only_record_is_accepted_and_stays_unverified(self):
        record = ci_only_record()
        self.assertEqual("b" * 40, validate_acceptance(record, "0.4.4"))

    def test_ci_only_must_state_no_physical_verification(self):
        record = ci_only_record()
        record["physical_device_verified"] = True
        with self.assertRaises(ValueError):
            validate_acceptance(record, "0.4.4")

    def test_ci_only_requires_pinned_successful_run(self):
        mutations = (
            lambda r: r["ci_run"].update(run_id=0),
            lambda r: r["ci_run"].update(conclusion="failure"),
            lambda r: r["ci_run"].update(url="https://example.com/run"),
            lambda r: r.pop("ci_run"),
        )
        for mutate in mutations:
            record = ci_only_record()
            mutate(record)
            with self.assertRaises(ValueError):
                validate_acceptance(record, "0.4.4")

    def test_ci_only_cannot_claim_phone_only_gate(self):
        record = ci_only_record()
        record["gates"]["physical_device_chat_stream_attachments_notifications"] = {
            "status": "passed", "evidence": "not actually tested"
        }
        with self.assertRaises(ValueError):
            validate_acceptance(record, "0.4.4")

    def test_ci_only_must_mark_phone_metrics_unmeasured(self):
        record = ci_only_record()
        record["performance"]["measured"] = True
        with self.assertRaises(ValueError):
            validate_acceptance(record, "0.4.4")
        record = ci_only_record()
        record["performance"]["note"] = " "
        with self.assertRaises(ValueError):
            validate_acceptance(record, "0.4.4")

    def test_ci_only_must_list_unverified_gates(self):
        record = ci_only_record()
        del record["unverified_gates"]
        with self.assertRaises(ValueError):
            validate_acceptance(record, "0.4.4")

    def test_ci_only_requires_ci_verified_gates(self):
        record = ci_only_record()
        del record["gates"][CI_PROVEN_GATES[0]]
        with self.assertRaises(ValueError):
            validate_acceptance(record, "0.4.4")


if __name__ == "__main__":
    unittest.main()
