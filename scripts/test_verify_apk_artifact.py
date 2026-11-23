"""verify_apk_artifact.py 的自测：用构造出来的假 APK（zip）覆盖正例与各类反例。

这些用例只用 zipfile 拼字节，不依赖 Android SDK、不联网。
"""
import io
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from verify_apk_artifact import EXIT_FAILED, EXIT_OK, EXIT_USAGE, main, verify_apk  # noqa: E402

# 一个「够大、含版本信息」的假 dex：真 dex 头是 112 字节，这里只要大于阈值即可
def fake_dex(*strings: str, size: int = 4096) -> bytes:
    body = b"dex\n035\x00"
    body += b"\x00".join(s.encode("utf-8") for s in strings)
    if len(body) < size:
        body += b"\x00" * (size - len(body))
    return body


def build_apk(path: Path, entries: dict, with_manifest: bool = True) -> Path:
    with zipfile.ZipFile(path, "w") as archive:
        if with_manifest:
            archive.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00binary-manifest")
        for name, data in entries.items():
            archive.writestr(name, data)
    return path


class VerifyApkArtifactTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def apk(self, name="app.apk", classes=None, with_manifest=True):
        return build_apk(
            self.root / name,
            classes if classes is not None else {"classes.dex": fake_dex("0.8.6", "32", "history_memory_task_log")},
            with_manifest=with_manifest,
        )

    def test_valid_artifact_passes(self):
        report = verify_apk(
            self.apk(),
            version_name="0.8.6",
            version_code=32,
            require_markers=["history_memory_task_log"],
        )
        self.assertTrue(report.ok, report.failures)
        self.assertEqual(["classes.dex"], report.dex_entries)

    def test_missing_version_name_fails(self):
        report = verify_apk(self.apk(), version_name="0.8.7")
        self.assertFalse(report.ok)
        self.assertTrue(any("versionName" in f for f in report.failures), report.failures)

    def test_missing_version_code_fails(self):
        report = verify_apk(self.apk(), version_code=33)
        self.assertFalse(report.ok)
        self.assertTrue(any("versionCode" in f for f in report.failures), report.failures)

    def test_required_marker_absent_fails(self):
        report = verify_apk(self.apk(), require_markers=["memory_task_history"])
        self.assertFalse(report.ok)
        self.assertTrue(any("memory_task_history" in f for f in report.failures), report.failures)

    def test_forbidden_marker_present_fails(self):
        report = verify_apk(self.apk(), forbid_markers=["history_memory_task_log"])
        self.assertFalse(report.ok)
        self.assertTrue(any("应当已删除" in f for f in report.failures), report.failures)

    def test_forbidden_marker_absent_passes(self):
        report = verify_apk(self.apk(), forbid_markers=["legacy_update_banner"])
        self.assertTrue(report.ok, report.failures)

    def test_empty_dex_fails(self):
        report = verify_apk(self.apk(classes={"classes.dex": b"dex\n"}), version_name="0.8.6")
        self.assertFalse(report.ok)
        self.assertTrue(any("内容为空或损坏" in f for f in report.failures), report.failures)

    def test_no_dex_fails(self):
        report = verify_apk(self.apk(classes={"resources.arsc": b"x" * 64}))
        self.assertFalse(report.ok)
        self.assertTrue(any("classes*.dex 数量不足" in f for f in report.failures), report.failures)

    def test_missing_manifest_fails(self):
        report = verify_apk(self.apk(with_manifest=False))
        self.assertFalse(report.ok)
        self.assertTrue(any("AndroidManifest.xml" in f for f in report.failures), report.failures)

    def test_not_a_zip_fails_without_crashing(self):
        path = self.root / "broken.apk"
        path.write_bytes(b"not a zip at all")
        report = verify_apk(path)
        self.assertFalse(report.ok)
        self.assertTrue(any("不是合法的 APK" in f for f in report.failures), report.failures)

    def test_missing_file_fails(self):
        report = verify_apk(self.root / "nope.apk")
        self.assertFalse(report.ok)
        self.assertTrue(any("不存在" in f for f in report.failures), report.failures)

    def test_multi_dex_is_searched_together(self):
        path = self.apk(
            classes={
                "classes.dex": fake_dex("0.8.6"),
                "classes2.dex": fake_dex("history_memory_task_log", size=512),
            }
        )
        report = verify_apk(path, version_name="0.8.6", require_markers=["history_memory_task_log"])
        self.assertTrue(report.ok, report.failures)
        self.assertEqual(["classes.dex", "classes2.dex"], report.dex_entries)

    def test_min_dex_entries_is_enforced(self):
        report = verify_apk(self.apk(), min_dex_entries=2)
        self.assertFalse(report.ok)


class VerifyApkArtifactCliTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.apk = build_apk(
            self.root / "app.apk",
            {"classes.dex": fake_dex("0.8.6", "32", "history_memory_task_log")},
        )

    def test_cli_exit_zero_on_success(self):
        code = main(["--apk", str(self.apk), "--version-name", "0.8.6", "--version-code", "32"])
        self.assertEqual(EXIT_OK, code)

    def test_cli_exit_one_on_mismatch(self):
        code = main(["--apk", str(self.apk), "--version-name", "9.9.9"])
        self.assertEqual(EXIT_FAILED, code)

    def test_cli_exit_two_on_missing_file(self):
        code = main(["--apk", str(self.root / "missing.apk")])
        self.assertEqual(EXIT_USAGE, code)

    def test_cli_json_report_is_machine_readable(self):
        buffer = io.StringIO()
        stdout, sys.stdout = sys.stdout, buffer
        try:
            code = main(["--apk", str(self.apk), "--version-name", "9.9.9", "--json"])
        finally:
            sys.stdout = stdout
        self.assertEqual(EXIT_FAILED, code)
        payload = json.loads(buffer.getvalue())
        self.assertFalse(payload["ok"])
        self.assertTrue(payload["failures"])

    def test_script_entrypoint_returns_exit_code(self):
        script = Path(__file__).resolve().parent / "verify_apk_artifact.py"
        done = subprocess.run(
            [sys.executable, str(script), "--apk", str(self.apk), "--version-code", "32"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(EXIT_OK, done.returncode, done.stdout + done.stderr)
        self.assertIn("结论：通过", done.stdout)


if __name__ == "__main__":
    unittest.main()
