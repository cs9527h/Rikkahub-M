#!/usr/bin/env python3
"""Fail closed for stable releases; never manufacture acceptance evidence."""
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


def validate_acceptance(data, version):
    if data.get("version") != version:
        raise ValueError("Acceptance evidence version does not match the release")
    sha = data.get("candidate_sha", "")
    if not isinstance(sha, str) or not re.fullmatch(r"[a-f0-9]{40}", sha):
        raise ValueError("Acceptance evidence must pin the exact tested commit")
    if data.get("device_kind") != "physical":
        raise ValueError("An emulator does not satisfy physical-device acceptance")
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
    return sha


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("version")
    parser.add_argument("evidence", type=Path)
    args = parser.parse_args()
    candidate = validate_acceptance(json.loads(args.evidence.read_text()), args.version)
    # Evidence may be committed after a tested candidate, but no other tracked file may change.
    subprocess.run(["git", "cat-file", "-e", candidate + "^{commit}"], check=True)
    subprocess.run([
        "git", "diff", "--exit-code", candidate, "HEAD", "--", ".",
        ":(exclude)docs/releases/*-acceptance.json",
    ], check=True, stdout=subprocess.DEVNULL)
    print("Stable release acceptance verified against unchanged tested source:", candidate)


if __name__ == "__main__":
    main()
