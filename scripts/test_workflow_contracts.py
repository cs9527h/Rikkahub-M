"""Repository-specific safety wiring checks; actionlint validates YAML/Actions syntax separately."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parent.parent
RELEASE = (ROOT / ".github/workflows/release.yml").read_text()
PREVIEW = (ROOT / ".github/workflows/mate-v1-preview.yml").read_text()


def job(workflow, name):
    match = re.search(r"(?ms)^  " + re.escape(name) + r":\n(.*?)(?=^  [a-z][a-z0-9_-]*:\n|\Z)", workflow)
    if not match:
        raise AssertionError("Missing job: " + name)
    return match[1]


class WorkflowContractsTest(unittest.TestCase):
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

    def test_stable_verifier_precedes_release_creation_and_keeps_pinned_source(self):
        publish = job(RELEASE, "publish")
        self.assertLess(publish.index("python3 scripts/verify_release_acceptance.py"), publish.index("gh release create"))
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
        self.assertIn('manifest version-code "$apk")" -gt 15', RELEASE)
        self.assertIn('"$cert" = "$EXPECTED_CERT_SHA256"', RELEASE)
        self.assertIn('"$pkg" = "com.rikkahub.mate"', RELEASE)
        self.assertIn('"${#apks[@]}" -eq 1', RELEASE)

    def test_full_instrumentation_static_analysis_and_failure_reports_are_wired(self):
        for workflow in (RELEASE, PREVIEW):
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
        self.assertNotIn("continue-on-error:", PREVIEW)

    def test_signed_upgrade_uses_release_package_artifacts_and_data_contract(self):
        script = (ROOT / "scripts/compare-installed-screens.sh").read_text()
        self.assertIn("signed) candidate_dir=dist; candidate_package=com.rikkahub.mate", script)
        self.assertIn("python3 scripts/upgrade_data_contract.py prepare", script)
        self.assertIn("python3 scripts/upgrade_data_contract.py verify --version-code 16", script)
        self.assertIn('capture "$candidate_package" candidate', script)
        self.assertNotIn('capture com.rikkahub.mate.debug candidate', script)
        self.assertIn('ERROR: $label($theme) window not confirmed', script)
        self.assertNotIn('WARN: $label($theme) window not confirmed', script)

    def test_workflow_secrets_are_environment_values_not_interpolated_shell(self):
        for workflow in (RELEASE, PREVIEW):
            self.assertIn('"$STORE_PASSWORD"', workflow)
            self.assertNotIn('echo "storePassword=${{', workflow)
            self.assertNotIn('echo "keyPassword=${{', workflow)


if __name__ == "__main__":
    unittest.main()
