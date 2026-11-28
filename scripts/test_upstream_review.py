#!/usr/bin/env python3
"""Real temporary Git histories; no network, project build, global config or remote service."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import upstream_review as review


SOURCE = "ai/src/main/java/Example.kt"
OTHER = "common/src/main/java/Other.kt"


class UpstreamReviewTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="upstream-review-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / "working"
        self.repo.mkdir()
        self.origin = self.root / "origin.git"
        subprocess.run(["git", "init", "--bare", "--quiet", str(self.origin)], check=True, capture_output=True)
        subprocess.run(["git", "init", "--quiet", "-b", "master", str(self.repo)], check=True, capture_output=True)
        self.git = review.Git(self.repo)
        self.git.run("remote", "add", "origin", str(self.origin))
        self.baseline = self.commit(SOURCE, "base\n", "initial source")
        state = {"schema": 1, "reviewed_upstream": self.baseline, "last_review": None}
        self.commit(review.STATE_PATH, json.dumps(state), "local review state")
        self.master = self.git.text("rev-parse", "HEAD")
        self.git.run("branch", "upstream", self.baseline)

    def commit(self, path, content, title):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        self.git.run("add", "--", path)
        self.git.run("commit", "--quiet", "-m", title)
        return self.git.text("rev-parse", "HEAD")

    def upstream(self, path=SOURCE, content="fixed\n", title="fix: handle parser input"):
        self.git.run("switch", "upstream")
        sha = self.commit(path, content, title)
        self.git.run("switch", "master")
        return sha

    def planned(self, head):
        return review.plan(self.git, self.baseline, head)

    def start(self):
        return review.create_branch(self.git, "2026-12", "master")

    def call_main(self, *options):
        original = review.Git.run
        fetched = []
        def local_fetch(git, *args, **kwargs):
            if args[0] == "fetch":
                fetched.append(args)
                self.assertEqual(("fetch", "--no-tags", review.UPSTREAM_URL, "HEAD"), args)
                return original(git, "fetch", "--no-tags", ".", "upstream")
            return original(git, *args, **kwargs)
        output = self.root / "artifacts"
        argv = ["upstream_review.py", "--repo", str(self.repo), "--output-dir", str(output),
                "--month", "2026-12", "--base", "master", *options]
        with mock.patch.object(review.Git, "run", local_fetch), mock.patch("sys.argv", argv), contextlib.redirect_stdout(io.StringIO()):
            code = review.main()
        return code, output, fetched

    def test_same_baseline_has_no_changes_no_branch_and_real_artifacts(self):
        code, output, fetched = self.call_main("--apply")
        self.assertEqual(0, code)
        self.assertTrue(fetched)
        self.assertEqual([], json.loads((output / "review.json").read_text())["items"])
        self.assertIn("No upstream changes", (output / "review.md").read_text())
        self.assertEqual("master", self.git.text("branch", "--show-current"))
        self.assertNotEqual(0, self.git.run("show-ref", "--verify", "--quiet", "refs/heads/sync/upstream-2026-12", check=False).returncode)

    def test_fix_applies_only_to_isolation_branch_and_metadata_keeps_baseline(self):
        head = self.upstream()
        code, output, _ = self.call_main("--apply")
        self.assertEqual(0, code)
        data = json.loads((output / "review.json").read_text())
        self.assertEqual("applied", data["items"][0]["status"])
        self.assertEqual(head, data["items"][0]["sha"])
        self.assertIn("cherry picked from commit", self.git.text("log", "-2", "--format=%B"))
        self.assertEqual(self.master, self.git.text("rev-parse", "master"))
        state = review.load_state(self.repo)
        self.assertEqual(self.baseline, state["reviewed_upstream"])
        self.assertEqual(data, state["last_review"])
        self.git.clean()

    def test_feature_breaking_merge_and_protected_paths_stay_manual(self):
        paths = [".github/workflows/release.yml", "build.gradle.kts", "gradle/libs.versions.toml",
                 "scripts/task.py", "app/src/main/java/Version.kt", "package.json",
                 "app/src/main/java/me/rerere/rikkahub/data/Storage.kt", "workspace/src/main/java/Files.kt"]
        for path in paths:
            category, _ = review.classify("fix: update config", [path], 1, False)
            self.assertEqual("manual", category, path)
        for title in ["feat: new feature", "fix!: changed API", "fix(core)!: changed API", "fix: issue\n\nBREAKING CHANGE: state reset"]:
            self.assertEqual("manual", review.classify(title, [SOURCE], 1, False)[0])
        self.assertEqual("manual", review.classify("fix: merged", [SOURCE], 2, False)[0])
        head = self.upstream(title="feat: new parser mode")
        data = self.planned(head)
        branch = self.start()
        self.assertTrue(review.apply_plan(self.git, data, branch))
        self.assertEqual("manual", data["items"][0]["status"])
        self.assertEqual("base\n", (self.repo / SOURCE).read_text())

    def test_fix_depending_on_deferred_same_path_stays_manual(self):
        self.upstream(title="feat: new parser mode")
        head = self.upstream(content="fix mode\n", title="fix: repair new mode")
        data = self.planned(head)
        self.assertEqual(["manual", "manual"], [i["status"] for i in data["items"]])
        self.assertIn("earlier deferred", data["items"][1]["reason"])

    def test_conflict_aborts_preserves_local_content_and_blocks_later_fix(self):
        first = self.upstream()
        head = self.upstream(OTHER, "second fix\n", "fix: independent later correction")
        self.commit(SOURCE, "local customization\n", "local: customize parser")
        data = self.planned(head)
        branch = self.start()
        before = self.git.text("rev-parse", "HEAD")
        self.assertFalse(review.apply_plan(self.git, data, branch))
        self.assertEqual(["conflict", "blocked"], [i["status"] for i in data["items"]])
        self.assertEqual(first, data["items"][0]["sha"])
        self.assertTrue(data["items"][0]["evidence"])
        self.assertEqual(before, self.git.text("rev-parse", "HEAD"))
        self.assertEqual("local customization\n", (self.repo / SOURCE).read_text())
        self.assertFalse((self.repo / OTHER).exists())
        self.git.clean()
        self.assertIn("Failure evidence", review.render(data))

    def test_main_records_conflict_and_fails_even_after_metadata_commit(self):
        self.upstream()
        self.commit(SOURCE, "local\n", "local customize")
        code, output, _ = self.call_main("--apply")
        self.assertEqual(2, code)
        self.assertEqual("conflict", json.loads((output / "status.json").read_text())["status"])
        self.assertEqual("conflict", review.load_state(self.repo)["last_review"]["items"][0]["status"])
        self.git.clean()

    def test_dirty_tree_and_non_isolation_branch_stop_without_applying(self):
        head = self.upstream()
        data = self.planned(head)
        with self.assertRaises(review.ReviewError):
            review.apply_plan(self.git, data, "sync/upstream-2026-12")
        (self.repo / "untracked.txt").write_text("do not touch")
        with self.assertRaises(review.ReviewError):
            self.start()
        self.assertEqual("master", self.git.text("branch", "--show-current"))
        self.assertEqual("do not touch", (self.repo / "untracked.txt").read_text())

    def test_dirty_tree_after_branch_creation_stops_application(self):
        data = self.planned(self.upstream())
        branch = self.start()
        (self.repo / SOURCE).write_text("in-progress user edit\n")
        with self.assertRaises(review.ReviewError):
            review.apply_plan(self.git, data, branch)
        self.assertEqual("in-progress user edit\n", (self.repo / SOURCE).read_text())

    def test_existing_local_or_remote_month_branch_never_overwritten(self):
        branch = "sync/upstream-2026-12"
        self.git.run("branch", branch)
        with self.assertRaises(review.ReviewError):
            self.start()
        self.assertEqual(self.master, self.git.text("rev-parse", branch))
        self.git.run("branch", "-D", branch)
        self.git.run("push", "origin", f"HEAD:refs/heads/{branch}")
        with self.assertRaises(review.ReviewError):
            self.start()
        self.assertEqual(self.master, self.git.text("ls-remote", "origin", f"refs/heads/{branch}").split()[0])

    def test_equivalent_patch_and_repeated_execution_are_idempotent(self):
        data = self.planned(self.upstream())
        branch = self.start()
        review.apply_plan(self.git, data, branch)
        review.write_metadata(self.git, data, review.load_state(self.repo), branch)
        before = self.git.text("rev-parse", "HEAD")
        self.assertEqual("equivalent", self.planned(data["upstream_head"])["items"][0]["status"])
        state = (self.repo / review.STATE_PATH).read_bytes()
        with self.assertRaises(review.ReviewError):
            review.create_branch(self.git, "2026-12", branch)
        self.assertEqual(before, self.git.text("rev-parse", "HEAD"))
        self.assertEqual(state, (self.repo / review.STATE_PATH).read_bytes())

    def test_malicious_title_and_paths_are_data_not_shell(self):
        title = "fix: $(touch PWNED) `touch ALSO_PWNED` | @all <script>alert(1)</script>"
        path = "ai/src/main/java/$(touch PWNED).kt"
        data = self.planned(self.upstream(path, "safe content\n", title))
        branch = self.start()
        self.assertTrue(review.apply_plan(self.git, data, branch))
        self.assertEqual("applied", data["items"][0]["status"])
        self.assertFalse((self.repo / "PWNED").exists())
        self.assertFalse((self.repo / "ALSO_PWNED").exists())
        self.assertTrue((self.repo / path).exists())
        report = review.render(data)
        self.assertNotIn("<script>", report)
        self.assertNotIn("@all", report)
        self.assertIn("&#124;", report)
        self.assertIn("&#96;", report)

    def test_control_character_path_symlink_and_executable_stay_manual(self):
        head = self.upstream("ai/src/main/java/line\nbreak.kt", "text", "fix: unusual path")
        self.assertEqual("manual", self.planned(head)["items"][0]["status"])
        self.git.run("switch", "upstream")
        symlink = self.repo / "ai/src/main/java/link.kt"
        symlink.symlink_to("/tmp/not-owned")
        self.git.run("add", "--", str(symlink))
        self.git.run("commit", "-m", "fix: symbolic link")
        head = self.git.text("rev-parse", "HEAD")
        self.git.run("switch", "master")
        self.assertEqual("manual", self.planned(head)["items"][-1]["status"])
        self.assertEqual("manual", review.classify("fix: executable", [SOURCE], 1, True)[0])

    def test_base_head_changed_after_plan_stops(self):
        data = self.planned(self.upstream())
        self.commit(OTHER, "local work\n", "local other work")
        branch = self.start()
        with self.assertRaisesRegex(review.ReviewError, "moved after planning"):
            review.apply_plan(self.git, data, branch)

    def test_rewritten_upstream_and_invalid_month_fail_closed(self):
        self.git.run("checkout", "--orphan", "unrelated")
        self.git.run("rm", "-r", "-f", "--ignore-unmatch", ".")
        other = self.commit("other", "new root", "new history")
        self.git.run("switch", "master")
        with self.assertRaisesRegex(review.ReviewError, "ancestor"):
            self.planned(other)
        for month in ["2026-13", "2026-00", "../master", "2026-12;touch PWNED"]:
            with self.assertRaises(review.ReviewError):
                review.branch_name(month)

    def test_publish_push_is_create_only_and_pr_denial_is_explicit(self):
        data = self.planned(self.upstream())
        branch = self.start()
        review.apply_plan(self.git, data, branch)
        report = self.root / "review.md"
        report.write_text(review.render(data))
        original = subprocess.run
        def deny_pr(command, *args, **kwargs):
            if command[0] == "gh":
                self.assertIn("--draft", command)
                self.assertIn("--body-file", command)
                return subprocess.CompletedProcess(command, 1, "", "GraphQL: Resource not accessible by integration")
            return original(command, *args, **kwargs)
        with mock.patch.object(subprocess, "run", deny_pr):
            with self.assertRaisesRegex(review.ReviewError, "PR creation FAILED"):
                review.publish(self.git, branch, "master", report, "fixture/project")
        remote_before = self.git.text("ls-remote", "origin", f"refs/heads/{branch}").split()[0]
        self.commit(OTHER, "another local change", "local later")
        with self.assertRaises(review.ReviewError):
            review.publish(self.git, branch, "master", report, "fixture/project")
        self.assertEqual(remote_before, self.git.text("ls-remote", "origin", f"refs/heads/{branch}").split()[0])

    def test_invalid_publish_repo_and_symlinked_metadata_are_blocked(self):
        data = self.planned(self.upstream())
        branch = self.start()
        with self.assertRaises(review.ReviewError):
            review.publish(self.git, branch, "master", self.root / "report", "bad;command/project")
        # A symlink already tracked by the trusted base still must not be followed by metadata writes.
        ledger = self.repo / review.LEDGER_PATH
        ledger.parent.mkdir(parents=True, exist_ok=True)
        outside = self.root / "outside.md"
        outside.write_text("untouched")
        ledger.symlink_to(outside)
        self.git.run("add", "--", review.LEDGER_PATH)
        self.git.run("commit", "-m", "local symlink fixture")
        with self.assertRaisesRegex(review.ReviewError, "symlinked"):
            review.write_metadata(self.git, data, review.load_state(self.repo), branch)
        self.assertEqual("untouched", outside.read_text())

    def test_main_error_always_has_readable_failure_artifact(self):
        (self.repo / "unsaved.txt").write_text("unsaved")
        code, output, fetched = self.call_main("--apply")
        self.assertEqual(1, code)
        self.assertEqual([], fetched)
        status = json.loads((output / "status.json").read_text())
        self.assertEqual("failed", status["status"])
        self.assertIn("dirty", status["error"])
        self.assertIn("FAILED", (output / "review.md").read_text())

    def test_real_merge_commit_is_manual_and_source_binary_is_not_auto_applied(self):
        self.git.run("switch", "upstream")
        self.git.run("switch", "-c", "side")
        self.commit(OTHER, "side\n", "fix: side parser")
        self.git.run("switch", "upstream")
        self.commit("ai/src/main/java/Main.kt", "main\n", "fix: main parser")
        self.git.run("merge", "--no-ff", "side", "-m", "fix: merge side history")
        head = self.git.text("rev-parse", "HEAD")
        self.git.run("switch", "master")
        merge = self.planned(head)["items"][-1]
        self.assertEqual("manual", merge["status"])
        self.assertIn("merge/root", merge["reason"])
        binary = self.upstream("ai/src/main/java/Binary.kt", "binary\x00payload", "fix: binary disguised as source")
        self.assertEqual("manual", self.planned(binary)["items"][-1]["status"])

    def test_invalid_utf8_path_is_manual_and_report_remains_writable(self):
        head = self.upstream("ai/src/main/java/bad-\udcff.kt", "safe", "fix: invalid filename bytes")
        data = self.planned(head)
        self.assertEqual("manual", data["items"][0]["status"])
        (self.root / "report.md").write_text(review.render(data), encoding="utf-8")
        self.assertIn("\\udcff", (self.root / "report.md").read_text())

    def test_main_pr_permission_denial_keeps_branch_and_readable_failure(self):
        self.upstream()
        original = subprocess.run
        def denied(command, *args, **kwargs):
            if command[0] == "gh":
                return subprocess.CompletedProcess(command, 1, "", "GraphQL: Actions cannot create pull requests")
            return original(command, *args, **kwargs)
        with mock.patch.object(subprocess, "run", denied), mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": "fixture/project"}):
            code, output, _ = self.call_main("--apply", "--publish")
        self.assertEqual(1, code)
        status = json.loads((output / "status.json").read_text())
        self.assertIsNone(status["pr_url"])
        self.assertIn("PR creation FAILED", status["error"])
        self.assertIn("Actions cannot create", (output / "review.md").read_text())
        self.assertTrue(self.git.text("ls-remote", "origin", "refs/heads/sync/upstream-2026-12"))

    def test_successful_pr_records_actual_returned_url_and_rerun_never_overwrites(self):
        self.upstream()
        original = subprocess.run
        expected = "https://github.com/fixture/project/pull/7"
        def success(command, *args, **kwargs):
            if command[0] == "gh":
                return subprocess.CompletedProcess(command, 0, expected + "\n", "")
            return original(command, *args, **kwargs)
        with mock.patch.object(subprocess, "run", success), mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": "fixture/project"}):
            code, output, _ = self.call_main("--apply", "--publish")
        self.assertEqual(0, code)
        self.assertEqual(expected, json.loads((output / "status.json").read_text())["pr_url"])
        remote = self.git.text("ls-remote", "origin", "refs/heads/sync/upstream-2026-12")
        self.git.run("switch", "master")
        code, output, _ = self.call_main("--apply")
        self.assertEqual(1, code)
        self.assertIn("already exists", json.loads((output / "status.json").read_text())["error"])
        self.assertEqual(remote, self.git.text("ls-remote", "origin", "refs/heads/sync/upstream-2026-12"))
        self.assertEqual(self.master, self.git.text("rev-parse", "master"))

    def test_state_schema_and_fixed_initial_baseline(self):
        self.assertEqual("c08d720036857445038249220a9168009e3160a0", review.INITIAL_BASELINE)
        self.assertEqual("https://github.com/rikkahub/rikkahub.git", review.UPSTREAM_URL)
        target = self.repo / review.STATE_PATH
        target.write_text('{"schema":999,"reviewed_upstream":"x"}')
        with self.assertRaises(review.ReviewError):
            review.load_state(self.repo)


if __name__ == "__main__":
    unittest.main()
