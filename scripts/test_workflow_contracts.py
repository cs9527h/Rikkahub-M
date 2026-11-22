"""Repository-specific safety wiring checks; actionlint validates YAML/Actions syntax separately."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parent.parent
RELEASE = (ROOT / ".github/workflows/release.yml").read_text()
CI = (ROOT / ".github/workflows/ci-build.yml").read_text() if (ROOT / ".github/workflows/ci-build.yml").exists() else (ROOT / ".github/workflows/daily-build.yml").read_text()


def job(workflow, name):
    match = re.search(r"(?ms)^  " + re.escape(name) + r":\n(.*?)(?=^  [a-z][a-z0-9_-]*:\n|\Z)", workflow)
    if not match:
        raise AssertionError("Missing job: " + name)
    return match[1]


class WorkflowContractsTest(unittest.TestCase):
    def test_only_the_release_workflow_publishes_releases(self):
        workflows = list((ROOT / ".github/workflows").glob("*.yml"))
        names = {path.name for path in workflows}
        # 历史 nightly / 预览 / 一次性迁移工作流已移除，避免与正式发布链重复
        self.assertNotIn("mate-v1-preview.yml", names)
        self.assertNotIn("promote-043-stable.yml", names)
        publishers = [path.name for path in workflows if "gh release create" in path.read_text() or "softprops/action-gh-release" in path.read_text()]
        self.assertEqual(["release.yml"], publishers)
        # 日常检查只编译和测试，不产出安装包
        self.assertNotIn("assembleGithubRelease", CI)
        self.assertIn(":app:assembleGithubDebug", CI)

    def test_publish_requires_successful_build_and_upgrade(self):
        publish = job(RELEASE, "publish")
        needs = re.search(r"^    needs: \[([^]]+)\]$", publish, re.M)
        self.assertIsNotNone(needs)
        self.assertEqual({"build", "upgrade"}, {value.strip() for value in needs[1].split(",")})
        self.assertNotIn("continue-on-error:", RELEASE)
        self.assertIn("needs.build.result == 'success'", publish)
        self.assertIn("needs.upgrade.result == 'success'", publish)
        self.assertNotIn("always()", publish)
        self.assertIn("github.ref == 'refs/heads/master'", publish)
        self.assertIn("inputs.publish_release == true", publish)
        self.assertIn("github.event_name == 'workflow_dispatch'", publish)

    def test_stable_release_uses_automated_gates_and_keeps_pinned_source(self):
        publish = job(RELEASE, "publish")
        # 正式版门禁是可复现的自动化检查（build + upgrade + Lint + 签名/校验和），
        # 不再要求实体设备验收 JSON；但也不允许为此伪造任何验收记录。
        self.assertNotIn("verify_release_acceptance.py", publish)
        self.assertIn("gh release create", publish)
        self.assertIn("sha256sum -c SHA256SUMS.txt", publish)
        self.assertIn("fetch-depth: 0", publish)
        self.assertIn('--target "$GITHUB_SHA"', publish)
        self.assertIn('flags+=(--prerelease)', publish)
        self.assertIn('"${flags[@]}"', publish)
        self.assertNotIn("gh release upload", publish)

    def test_release_version_matches_android_source_and_increases_past_old_preview(self):
        source = (ROOT / "app/build.gradle.kts").read_text()
        version = re.search(r'versionName = "([^"]+)"', source)[1]
        code = int(re.search(r'versionCode = (\d+)', source)[1])
        self.assertIn("VERSION: '" + version + "'", RELEASE)
        self.assertGreater(code, 15)
        self.assertIn('manifest version-code "$apk")" -gt ' + str(code - 1), RELEASE)
        self.assertIn('"$cert" = "$EXPECTED_CERT_SHA256"', RELEASE)
        self.assertIn('"$pkg" = "com.rikkahub.mate"', RELEASE)
        self.assertIn('"${#apks[@]}" -eq 1', RELEASE)

    def test_full_instrumentation_static_analysis_and_failure_reports_are_wired(self):
        for workflow in (RELEASE,):
            self.assertIn(":app:lintGithubDebug", workflow)
            self.assertIn("script: bash scripts/run-connected-verification.sh", workflow)
            wrapper = (ROOT / "scripts/run-connected-verification.sh").read_text()
            self.assertIn(":app:connectedGithubDebugAndroidTest", wrapper)
            app_test_commands = [line for line in wrapper.splitlines() if ":app:connectedGithubDebugAndroidTest" in line]
            for command in app_test_commands:
                self.assertNotIn("testInstrumentationRunnerArguments.class=", command)
            self.assertIn("app/build/outputs/androidTest-results/**", workflow)
            self.assertIn("app/build/test-results/**", workflow)
            self.assertIn("app/schemas/**", workflow)
            self.assertIn("if: always()", workflow)
        self.assertNotIn("continue-on-error:", CI)

    def test_signed_upgrade_uses_release_package_artifacts_and_data_contract(self):
        script = (ROOT / "scripts/compare-installed-screens.sh").read_text()
        self.assertIn("signed) candidate_dir=dist; candidate_package=com.rikkahub.mate", script)
        self.assertIn("python3 scripts/upgrade_data_contract.py prepare", script)
        self.assertIn('python3 scripts/upgrade_data_contract.py verify --version-code "$expected_code"', script)
        self.assertIn('capture "$candidate_package" candidate', script)
        self.assertNotIn('capture com.rikkahub.mate.debug candidate', script)
        self.assertIn('ERROR: $label($theme) window not confirmed', script)
        self.assertNotIn('WARN: $label($theme) window not confirmed', script)

    def test_workflow_secrets_are_environment_values_not_interpolated_shell(self):
        # CI 日常检查不再接触签名密钥；只有正式发布工作流需要这条约束
        for workflow in (RELEASE,):
            self.assertIn('"$STORE_PASSWORD"', workflow)
            self.assertNotIn('echo "storePassword=${{', workflow)
            self.assertNotIn('echo "keyPassword=${{', workflow)


    def test_every_screen_has_a_navigation_entry(self):
        """Every `data object X : Screen` must be registered with entry<Screen.X> in RouteActivity."""
        import pathlib, re
        source = pathlib.Path("app/src/main/java/me/rerere/rikkahub/RouteActivity.kt").read_text(encoding="utf-8")
        defined = set(re.findall(r"data object ([A-Za-z0-9_]+) : Screen", source))
        registered = set(re.findall(r"entry<Screen\.([A-Za-z0-9_]+)>", source))
        missing = sorted(defined - registered)
        self.assertEqual(missing, [], "screens missing navigation entry: " + ", ".join(missing))

    def test_upgrade_verification_reads_version_code_from_gradle(self):
        """升级校验必须按真实 versionCode 校验，不能写死某个版本的数字。"""
        import pathlib
        source = pathlib.Path("scripts/compare-installed-screens.sh").read_text(encoding="utf-8")
        self.assertRegex(source, r"versionCode", "脚本必须从 app/build.gradle.kts 读取 versionCode")
        hardcoded = re.findall(r"upgrade_data_contract\.py verify --version-code ([0-9]+)", source)
        self.assertEqual(hardcoded, [], "升级校验不得写死 versionCode")



if __name__ == "__main__":
    unittest.main()
