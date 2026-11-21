"""Synthetic ADB fixtures only; NOT Android upgrade acceptance evidence."""
import copy
import hashlib
from pathlib import Path
import unittest

from upgrade_data_contract import DATA_DIR, LEGACY_DB, PACKAGE, prepare, verify


class FakeAdb:
    def __init__(self, files=None):
        self.files = dict(files or {})
        self.code = 12
        self.root = True
        self.calls = []

    def exists(self, path):
        return path in self.files

    def run(self, *args):
        self.calls.append(args)
        if args in (("root",), ("wait-for-device",)):
            return ""
        if args == ("shell", "id", "-u"):
            return "0\n" if self.root else "2000\n"
        if args == ("shell", "dumpsys", "package", PACKAGE):
            return "versionCode=" + str(self.code) + " minSdk=26\n"
        if args[:3] == ("shell", "stat", "-c"):
            return "10001\n"
        if args[:2] == ("shell", "sha256sum"):
            return hashlib.sha256(self.files[args[2]]).hexdigest() + "  " + args[2] + "\n"
        if args[0] == "push":
            self.files[args[2]] = Path(args[1]).read_bytes()
            return ""
        if args[:2] in (("shell", "am"), ("shell", "mkdir"), ("shell", "chown"), ("shell", "chmod")):
            return ""
        raise AssertionError("Unexpected synthetic ADB command: " + repr(args))


class UpgradeDataContractTest(unittest.TestCase):
    def test_fresh_emulator_gets_valid_sqlite_fixture_and_private_sentinels(self):
        adb = FakeAdb()
        record = prepare(adb)
        self.assertEqual("prepared", record["status"])
        self.assertEqual(3, len(record["files"]))
        self.assertTrue(adb.files[DATA_DIR + "/" + LEGACY_DB].startswith(b"SQLite format 3\x00"))
        self.assertEqual(PACKAGE, record["package"])
        self.assertIn("not chat-semantic migration", record["scope"])

    def test_existing_legacy_database_wal_and_shm_are_not_overwritten(self):
        originals = {
            DATA_DIR + "/" + LEGACY_DB + suffix: ("synthetic original " + suffix).encode()
            for suffix in ("", "-wal", "-shm")
        }
        adb = FakeAdb(originals)
        record = prepare(adb)
        for path, content in originals.items():
            self.assertEqual(content, adb.files[path])
        self.assertEqual(5, len(record["files"]))
        self.assertFalse(any(call[0] == "push" and call[-1] in originals for call in adb.calls))

    def test_increasing_same_package_upgrade_preserves_every_file(self):
        adb = FakeAdb()
        record = prepare(adb)
        unchanged = copy.deepcopy(record)
        adb.code = 16
        result = verify(adb, record, 16)
        self.assertEqual("passed", result["status"])
        self.assertEqual(16, result["version_after"])
        self.assertEqual(unchanged, record)

    def test_missing_legacy_backup_sentinel_blocks_upgrade(self):
        adb = FakeAdb()
        record = prepare(adb)
        path = next(entry["path"] for entry in record["files"] if "legacy-mate-backup" in entry["path"])
        del adb.files[DATA_DIR + "/" + path]
        adb.code = 16
        with self.assertRaisesRegex(ValueError, "removed or changed"):
            verify(adb, record, 16)

    def test_changed_legacy_database_blocks_upgrade(self):
        adb = FakeAdb()
        record = prepare(adb)
        adb.files[DATA_DIR + "/" + LEGACY_DB] = b"synthetic wrong content"
        adb.code = 16
        with self.assertRaisesRegex(ValueError, "removed or changed"):
            verify(adb, record, 16)

    def test_wrong_or_nonincreasing_version_blocks_upgrade(self):
        for code in (12, 15, 17):
            adb = FakeAdb()
            record = prepare(adb)
            adb.code = code
            with self.assertRaisesRegex(ValueError, "increasing same-package"):
                verify(adb, record, 16)

    def test_missing_or_duplicate_fixture_manifest_blocks_upgrade(self):
        adb = FakeAdb()
        record = prepare(adb)
        adb.code = 16
        for entries in ([], record["files"][:1], record["files"] + [record["files"][0]]):
            with self.assertRaisesRegex(ValueError, "Incomplete"):
                verify(adb, {**record, "files": entries}, 16)

    def test_wrong_package_and_unsafe_fixture_path_are_rejected(self):
        adb = FakeAdb()
        record = prepare(adb)
        adb.code = 16
        with self.assertRaisesRegex(ValueError, "Unexpected upgrade"):
            verify(adb, {**record, "package": PACKAGE + ".debug"}, 16)
        record["files"].append({"path": "../other-private-file", "sha256": "0" * 64})
        with self.assertRaisesRegex(ValueError, "Unexpected fixture path"):
            verify(adb, record, 16)

    def test_no_root_is_a_failure_not_a_fake_acceptance(self):
        adb = FakeAdb()
        adb.root = False
        with self.assertRaisesRegex(RuntimeError, "Rootable disposable emulator"):
            prepare(adb)
        self.assertEqual({}, adb.files)

    def test_contract_never_uninstalls_or_clears_data(self):
        adb = FakeAdb()
        record = prepare(adb)
        adb.code = 16
        verify(adb, record, 16)
        for call in adb.calls:
            self.assertNotIn("uninstall", call)
            self.assertNotIn("clear", call)


if __name__ == "__main__":
    unittest.main()
