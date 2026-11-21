#!/usr/bin/env python3
"""Fail closed for stable releases; never manufacture acceptance evidence.

Two honest evidence modes are accepted:

* ``device_kind: "physical"`` - the historical full acceptance record: every gate and every
  measured performance metric has to come from a real phone run.
* ``device_kind: "ci-only"`` - the mode this fork uses for releases gated by CI (compile,
  JVM unit, emulator instrumented, lint, signature/version checks) without a physical-device
  acceptance record. It must state ``physical_device_verified: false``, name the exact CI
  run, and list the device-only gates as explicitly unverified. Nothing in this mode is
  reported as a physical-device result.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess

REQUIRED_GATES = (
    "compile_unit_static", "ui_database_migration", "physical_device_chat_stream_attachments_notifications",
    "cross_session_prompt_injection", "local_memory_recall_edit_isolation", "encrypted_cross_device_restore",
    "production_backup_storage", "signed_in_place_upgrade", "universal_apk_version_signature",
)
REQUIRED_METRICS = ("cold_start_ms", "scroll_frame_p95_ms", "memory_mb")

# Gates a CI run actually proves; the rest still need a phone and must be listed as unverified.
CI_PROVEN_GATES = ("compile_unit_static", "ui_database_migration", "signed_in_place_upgrade",
                   "universal_apk_version_signature")


def _check_sha(data):
    sha = data.get("candidate_sha", "")
    if not isinstance(sha, str) or not re.fullmatch(r"[a-f0-9]{40}", sha):
        raise ValueError("Acceptance evidence must pin the exact tested commit")
    return sha


def _validate_physical(data):
    gates = data.get("gates", {})
    for name in REQUIRED_GATES:
        gate = gates.get(name, {})
        if gate.get("status") != "passed" or not isinstance(gate.get("evidence"), str) or not gate["evidence"].strip():
            raise ValueError("Missing verified acceptance gate: " + name)
    for name in REQUIRED_METRICS:
        metric = data.get("performance", {}).get(name, {})
        for key in ("before", "after"):
            value = metric.get(key)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 < value < float("inf"):
                raise ValueError("Missing finite measured performance metric: " + name)
        if not isinstance(metric.get("evidence"), str) or not metric["evidence"].strip():
            raise ValueError("Missing performance evidence: " + name)
        if metric["after"] > metric["before"] * 1.05:
            raise ValueError("Performance regression over 5%: " + name)


def _validate_ci_only(data):
    if data.get("physical_device_verified") is not False:
        raise ValueError("CI-only evidence must state physical_device_verified: false")
    run = data.get("ci_run", {})
    run_id = run.get("run_id")
    if isinstance(run_id, bool) or not isinstance(run_id, int) or run_id <= 0:
        raise ValueError("CI-only evidence must pin the CI run id")
    if run.get("conclusion") != "success":
        raise ValueError("CI run must be successful")
    url = run.get("url")
    if not isinstance(url, str) or "actions/runs/" + str(run_id) not in url:
        raise ValueError("CI run url must point at the recorded run id")
    gates = data.get("gates", {})
    for name in CI_PROVEN_GATES:
        gate = gates.get(name, {})
        if gate.get("status") != "passed" or not isinstance(gate.get("evidence"), str) or not gate["evidence"].strip():
            raise ValueError("Missing CI-verified gate: " + name)
    unverified = data.get("unverified_gates")
    if not isinstance(unverified, list) or "physical_device_stability" not in unverified:
        raise ValueError("CI-only evidence must list unverified_gates")
    for name in REQUIRED_GATES:
        gate = gates.get(name)
        if isinstance(gate, dict) and gate.get("status") == "passed" and name not in CI_PROVEN_GATES:
            raise ValueError("A phone-only gate cannot be reported as passed in CI-only mode: " + name)
    perf = data.get("performance", {})
    if perf.get("measured") is not False:
        raise ValueError("CI-only evidence must mark performance as not measured on a phone")
    if not isinstance(perf.get("note"), str) or not perf["note"].strip():
        raise ValueError("CI-only evidence must explain the missing phone metrics")


def validate_acceptance(data, version):
    if data.get("version") != version:
        raise ValueError("Acceptance evidence version does not match the release")
    sha = _check_sha(data)
    kind = data.get("device_kind")
    if kind == "physical":
        _validate_physical(data)
        return sha
    if kind == "ci-only":
        _validate_ci_only(data)
        return sha
    raise ValueError("An emulator does not satisfy physical-device acceptance")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("version")
    parser.add_argument("evidence", type=Path)
    args = parser.parse_args()
    data = json.loads(args.evidence.read_text())
    candidate = validate_acceptance(data, args.version)
    # Evidence may be committed after a tested candidate, but no other tracked file may change.
    subprocess.run(["git", "cat-file", "-e", candidate + "^{commit}"], check=True)
    subprocess.run([
        "git", "diff", "--exit-code", candidate, "HEAD", "--", ".",
        ":(exclude)docs/releases/*-acceptance.json",
    ], check=True, stdout=subprocess.DEVNULL)
    if data.get("device_kind") == "ci-only":
        print("WARNING: accepted CI-only evidence for", args.version,
              "- no physical-device acceptance was recorded (listed as unverified).")
    print("Stable release acceptance verified against unchanged tested source:", candidate)


if __name__ == "__main__":
    main()
