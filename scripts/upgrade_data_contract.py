#!/usr/bin/env python3
"""Synthetic emulator-only, same-package upgrade data contract; never touches a user's phone."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
import uuid

PACKAGE = "com.rikkahub.mate"
DATA_DIR = "/data/user/0/" + PACKAGE
LEGACY_DB = "databases/mate_liyu_v1.db"


class Adb:
    def run(self, *args):
        return subprocess.run(
            ["adb", *args], check=True, capture_output=True, text=True, timeout=60,
        ).stdout.replace("\r", "")

    def exists(self, path):
        result = subprocess.run(
            ["adb", "shell", "test", "-f", path], capture_output=True, timeout=60,
        )
        if result.returncode not in (0, 1):
            raise RuntimeError("Cannot check emulator fixture")
        return result.returncode == 0


def root_emulator(adb):
    # This script is ONLY invoked in the disposable CI emulator, not through phone control.
    adb.run("root")
    adb.run("wait-for-device")
    if adb.run("shell", "id", "-u").strip() != "0":
        raise RuntimeError("Rootable disposable emulator required for data-preservation proof")
    adb.run("shell", "am", "force-stop", PACKAGE)


def version_code(adb):
    match = re.search(r"\bversionCode=(\d+)\b", adb.run("shell", "dumpsys", "package", PACKAGE))
    if not match:
        raise RuntimeError("Installed same-package version cannot be confirmed")
    return int(match[1])


def digest(adb, relative):
    raw = adb.run("shell", "sha256sum", DATA_DIR + "/" + relative).split()
    if not raw or not re.fullmatch(r"[a-fA-F0-9]{64}", raw[0]):
        raise RuntimeError("Cannot hash emulator fixture")
    return raw[0].lower()


def prepare(adb):
    root_emulator(adb)
    before = version_code(adb)
    owner = adb.run("shell", "stat", "-c", "%u", DATA_DIR).strip()
    if not owner.isdecimal() or not 10000 <= int(owner) < 2**31:
        raise RuntimeError("Cannot establish baseline application ownership")
    identity = uuid.uuid4().hex
    directories = ("files/upgrade-contract", "files/legacy-mate-backup", "databases")
    paths = []
    with tempfile.TemporaryDirectory(prefix="upgrade-contract-") as directory:
        local = Path(directory)
        for relative in directories:
            remote = DATA_DIR + "/" + relative
            adb.run("shell", "mkdir", "-p", remote)
            adb.run("shell", "chown", owner + ":" + owner, remote)
        sentinel = local / "sentinel.bin"
        sentinel.write_bytes(b"Synthetic upgrade fixture; contains no user data.\n")
        for relative in (
            "files/upgrade-contract/" + identity + ".bin",
            "files/legacy-mate-backup/upgrade-contract-" + identity + ".bin",
        ):
            remote = DATA_DIR + "/" + relative
            adb.run("push", str(sentinel), remote)
            adb.run("shell", "chown", owner + ":" + owner, remote)
            adb.run("shell", "chmod", "600", remote)
            paths.append(relative)
        # Never replace a pre-existing baseline database or fabricate invalid WAL/SHM files.
        if not adb.exists(DATA_DIR + "/" + LEGACY_DB):
            database = local / "legacy-fixture.db"
            with sqlite3.connect(database) as connection:
                connection.execute("CREATE TABLE upgrade_contract (content TEXT NOT NULL)")
                connection.execute("INSERT INTO upgrade_contract VALUES (?)", ("synthetic legacy row",))
            adb.run("push", str(database), DATA_DIR + "/" + LEGACY_DB)
            adb.run("shell", "chown", owner + ":" + owner, DATA_DIR + "/" + LEGACY_DB)
            adb.run("shell", "chmod", "600", DATA_DIR + "/" + LEGACY_DB)
        for suffix in ("", "-wal", "-shm"):
            relative = LEGACY_DB + suffix
            if adb.exists(DATA_DIR + "/" + relative):
                paths.append(relative)
        files = [{"path": path, "sha256": digest(adb, path)} for path in paths]
    return {
        "format_version": 1, "package": PACKAGE, "version_before": before, "files": files,
        "scope": "Legacy DB/sidecars, legacy backup directory and synthetic private files; not chat-semantic migration",
        "status": "prepared",
    }


def verify(adb, record, expected_code):
    if record.get("package") != PACKAGE or record.get("format_version") != 1:
        raise ValueError("Unexpected upgrade fixture manifest")
    entries = record.get("files", [])
    paths = [entry.get("path") for entry in entries]
    if (
        len(paths) < 3 or len(set(paths)) != len(paths) or LEGACY_DB not in paths
        or not any(str(path).startswith("files/upgrade-contract/") for path in paths)
        or not any(str(path).startswith("files/legacy-mate-backup/upgrade-contract-") for path in paths)
    ):
        raise ValueError("Incomplete upgrade fixture manifest")
    root_emulator(adb)
    installed = version_code(adb)
    if installed != expected_code or installed <= record["version_before"]:
        raise ValueError("Candidate is not an increasing same-package upgrade")
    for entry in record["files"]:
        relative = entry["path"]
        allowed = relative in (LEGACY_DB, LEGACY_DB + "-wal", LEGACY_DB + "-shm") or bool(re.fullmatch(
            r"files/(upgrade-contract/[a-f0-9]{32}|legacy-mate-backup/upgrade-contract-[a-f0-9]{32})\.bin", relative,
        ))
        if not allowed:
            raise ValueError("Unexpected fixture path")
        if not adb.exists(DATA_DIR + "/" + relative) or digest(adb, relative) != entry["sha256"]:
            raise ValueError("Upgrade removed or changed fixture: " + relative)
    return {**record, "version_after": installed, "status": "passed"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("prepare", "verify"))
    parser.add_argument("--manifest", type=Path, default=Path("evidence/signed-upgrade-data.json"))
    parser.add_argument("--version-code", type=int, default=16)
    args = parser.parse_args()
    adb = Adb()
    if args.phase == "prepare":
        record = prepare(adb)
    else:
        record = verify(adb, json.loads(args.manifest.read_text()), args.version_code)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps(record, indent=2) + "\n")
    print("UPGRADE_DATA_CONTRACT", record["status"], "files=", len(record["files"]), "scope=", record["scope"])


if __name__ == "__main__":
    main()
