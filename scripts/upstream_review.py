#!/usr/bin/env python3
"""Plan upstream deltas and attempt conservative fixes on a NEW monthly review branch.

No upstream code is executed. A clean cherry-pick is a review candidate, not approval.
All git/gh calls use argv, never a shell. The reviewed baseline advances only by humans.
"""
from __future__ import annotations

import argparse
import datetime as dt
import html
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tempfile
from typing import Any

UPSTREAM_URL = "https://github.com/rikkahub/rikkahub.git"
INITIAL_BASELINE = "c08d720036857445038249220a9168009e3160a0"
STATE_PATH = "docs/upstream-sync-state.json"
LEDGER_PATH = "docs/audit/upstream-review.md"
SHA = re.compile(r"[0-9a-f]{40}\Z")
MONTH = re.compile(r"\d{4}-(?:0[1-9]|1[0-2])\Z")
FIX = re.compile(r"fix(?:\([^)!\n]+\))?:\s+\S", re.IGNORECASE)
MODULES = {"app", "ai", "common", "document", "material3", "mediagen", "oauth",
           "search", "speech", "terminal", "ui", "web", "workspace"}
PROTECTED_PARTS = {".git", ".github", "gradle", "buildsrc", "build-logic", "scripts",
                   "node_modules", "vendor"}
PROTECTED_NAMES = {"gradlew", "gradlew.bat", "gradle.properties", "settings.gradle.kts",
                   "package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock",
                   "bun.lock", "bun.lockb", ".gitmodules", ".gitattributes", ".gitconfig"}
# Local persistence/lifecycle invariants need semantic adaptation, even for a clean fix.
LOCAL_INVARIANTS = ("app/src/main/java/me/rerere/rikkahub/data/",
                    "app/src/main/java/me/rerere/rikkahub/service/",
                    "workspace/src/main/")


class ReviewError(RuntimeError):
    pass


class Git:
    def __init__(self, root: Path):
        self.root = root.resolve()

    def run(self, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
        env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
        command = ["git", "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false",
                   "-c", "commit.gpgsign=false", "-c", "user.name=upstream-review[bot]",
                   "-c", "user.email=upstream-review[bot]@users.noreply.github.com", *args]
        result = subprocess.run(command, cwd=self.root, env=env, text=True,
                                encoding="utf-8", errors="surrogateescape",
                                capture_output=True, timeout=180)
        if check and result.returncode:
            raise ReviewError(f"git {args[0]} exit {result.returncode}: {evidence(result)}")
        return result

    def text(self, *args: str) -> str:
        return self.run(*args).stdout.strip()

    def clean(self) -> None:
        if self.text("status", "--porcelain=v1", "--untracked-files=all"):
            raise ReviewError("Worktree is dirty; application stopped without changing branches")
        for operation in ("CHERRY_PICK_HEAD", "MERGE_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply"):
            location = Path(self.text("rev-parse", "--git-path", operation))
            if not location.is_absolute():
                location = self.root / location
            if location.exists():
                raise ReviewError(f"Existing git operation {operation}; application stopped")


def evidence(result: subprocess.CompletedProcess[str]) -> str:
    value = (result.stderr + "\n" + result.stdout).strip()
    # Avoid persisting credential-bearing transport errors in artifacts.
    value = re.sub(r"(https?://)[^/@\s]+:[^/@\s]+@", r"\1[redacted]@", value)
    value = re.sub(r"gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+", "[redacted]", value)
    return value[:6000]


def validate_sha(value: str) -> str:
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise ReviewError("Expected a full lowercase commit SHA")
    return value


def branch_name(month: str) -> str:
    if not MONTH.fullmatch(month):
        raise ReviewError("Month must be YYYY-MM")
    return f"sync/upstream-{month}"


def protected_reason(path: str) -> str | None:
    parts = PurePosixPath(path).parts
    if not parts or path.startswith("/") or "\\" in path or any(
        ord(char) < 32 or ord(char) == 127 or 0xD800 <= ord(char) <= 0xDFFF for char in path
    ) or any(part in {"", ".", ".."} for part in path.split("/")):
        return "unusual/control/ambiguous path requires manual review"
    lowered = [part.lower() for part in parts]
    name = lowered[-1]
    if any(part in PROTECTED_PARTS for part in lowered) or name in PROTECTED_NAMES:
        return "CI/build/dependency/automation or git metadata path"
    if name.endswith((".gradle", ".gradle.kts")) or any(
        marker in name for marker in ("version", "release", "changelog")
    ):
        return "build/version/release policy path"
    if path.startswith(LOCAL_INVARIANTS):
        return "local persistence/workspace invariant requires semantic adaptation"
    if len(parts) < 3 or parts[0] not in MODULES or parts[1] != "src":
        return "outside conservative module-source allowlist"
    if PurePosixPath(path).suffix.lower() not in {".kt", ".java", ".xml", ".ts", ".tsx", ".js", ".css", ".html"}:
        return "non-source/binary/executable format requires manual review"
    return None


def changes(git: Git, sha: str) -> tuple[list[str], bool]:
    raw = git.run("diff-tree", "--root", "--no-commit-id", "--raw", "-z", "-r", "--no-renames", sha).stdout
    fields = raw.split("\0")
    paths: list[str] = []
    unsafe_mode = False
    for index in range(0, len(fields) - 1, 2):
        header, path = fields[index:index + 2]
        if not header.startswith(":"):
            raise ReviewError("Unexpected diff-tree record")
        modes = header[1:].split()[:2]
        if len(modes) != 2 or any(mode not in {"000000", "100644"} for mode in modes):
            unsafe_mode = True  # No symlink, gitlink, executable or mode conversion is auto-applied.
        paths.append(path)
    # A regular-file mode does not prove text: reject binary diffs even with a .kt/.xml suffix.
    for record in git.run("diff-tree", "--root", "--no-commit-id", "--numstat", "-z", "-r", "--no-renames", sha).stdout.split("\0"):
        if record and record.split("\t", 2)[:2] == ["-", "-"]:
            unsafe_mode = True
    # Do not traverse even a trusted base's symlinked parent while attempting a source patch.
    if any((git.root / path).resolve() != (git.root / path).absolute() for path in paths):
        unsafe_mode = True
    return paths, unsafe_mode


def classify(message: str, paths: list[str], parents: int, unsafe_mode: bool) -> tuple[str, str]:
    title = message.splitlines()[0] if message.splitlines() else ""
    if parents != 1:
        return "manual", "merge/root commit requires manual review"
    if "BREAKING CHANGE" in message.upper() or "BREAKING-CHANGE" in message.upper() or not FIX.match(title):
        return "manual", "not a non-breaking Conventional Commit fix"
    if not paths or unsafe_mode:
        return "manual", "empty, binary-mode, symlink, gitlink or executable change"
    for path in paths:
        reason = protected_reason(path)
        if reason:
            return "manual", f"{reason}: {path}"
    return "candidate", "non-breaking fix in permitted text-source paths; clean-pick attempt only"


def plan(git: Git, baseline: str, head: str) -> dict[str, Any]:
    validate_sha(baseline)
    validate_sha(head)
    if git.run("merge-base", "--is-ancestor", baseline, head, check=False).returncode:
        raise ReviewError("Reviewed baseline is not an upstream ancestor; inspect rewritten history manually")
    local = git.text("rev-parse", "HEAD")
    equivalents = {line.split()[1] for line in git.text("cherry", local, head, baseline).splitlines()
                   if line.startswith("- ")}
    items = []
    manual_paths: set[str] = set()
    for sha in git.text("rev-list", "--reverse", "--topo-order", f"{baseline}..{head}").splitlines():
        message = git.run("show", "-s", "--format=%B", sha).stdout
        parents = len(git.text("rev-list", "--parents", "-n", "1", sha).split()) - 1
        paths, unsafe = changes(git, sha)
        category, reason = classify(message, paths, parents, unsafe)
        status = "manual" if category == "manual" else "pending"
        if sha in equivalents or git.run("merge-base", "--is-ancestor", sha, local, check=False).returncode == 0:
            status, reason = "equivalent", "patch or commit already present locally"
        elif category == "candidate" and manual_paths.intersection(paths):
            category, status, reason = "manual", "manual", "touches a path changed by an earlier deferred commit"
        if status == "manual":
            manual_paths.update(paths)
        items.append({"sha": sha, "title": message.splitlines()[0] if message.splitlines() else "",
                      "paths": paths, "category": category, "status": status, "reason": reason})
    return {"schema": 1, "baseline": baseline, "upstream_head": head, "base_head": local,
            "upstream_url": UPSTREAM_URL, "items": items}


def require_review_branch(git: Git, branch: str) -> None:
    if not re.fullmatch(r"sync/upstream-\d{4}-(?:0[1-9]|1[0-2])", branch):
        raise ReviewError("Application requires a monthly isolation branch")
    if git.text("symbolic-ref", "--quiet", "--short", "HEAD") != branch:
        raise ReviewError("Current branch is not the expected monthly isolation branch")
    git.clean()


def create_branch(git: Git, month: str, base: str) -> str:
    branch = branch_name(month)
    git.clean()
    if git.text("symbolic-ref", "--quiet", "--short", "HEAD") != base:
        raise ReviewError("Branch creation must start from the configured default branch")
    if git.run("show-ref", "--verify", "--quiet", f"refs/heads/{branch}", check=False).returncode == 0:
        raise ReviewError(f"Monthly branch already exists locally: {branch}; never overwrite")
    remote = git.run("ls-remote", "--exit-code", "--heads", "origin", f"refs/heads/{branch}", check=False)
    if remote.returncode == 0:
        raise ReviewError(f"Monthly branch already exists remotely: {branch}; never overwrite")
    if remote.returncode != 2:
        raise ReviewError(f"Could not establish remote branch absence: {evidence(remote)}")
    git.run("switch", "--create", branch)
    return branch


def apply_plan(git: Git, review: dict[str, Any], branch: str) -> bool:
    require_review_branch(git, branch)
    if git.text("rev-parse", "HEAD") != review["base_head"]:
        raise ReviewError("Local HEAD moved after planning; replan without applying")
    blocked = False
    for item in review["items"]:
        if item["status"] != "pending":
            continue
        if blocked:
            item["status"], item["reason"] = "blocked", "earlier conflict/failed pick; application stopped"
            continue
        require_review_branch(git, branch)
        before = git.text("rev-parse", "HEAD")
        result = git.run("cherry-pick", "-x", validate_sha(item["sha"]), check=False)
        if result.returncode == 0:
            item["status"] = "applied"
            item["local_sha"] = git.text("rev-parse", "HEAD")
        else:
            item["status"] = "conflict"
            item["reason"] = "clean cherry-pick failed; aborted, all later candidate picks stopped"
            item["evidence"] = evidence(result)
            aborted = git.run("cherry-pick", "--abort", check=False)
            if aborted.returncode or git.text("rev-parse", "HEAD") != before:
                raise ReviewError(f"Cherry-pick abort failed; manual recovery required: {evidence(aborted)}")
            git.clean()
            blocked = True
    return not blocked


def printable(value: str) -> str:
    return value.encode("utf-8", errors="backslashreplace").decode("utf-8")


def markdown(value: str) -> str:
    return html.escape(printable(value), quote=True).replace("|", "&#124;").replace("`", "&#96;").replace("@", "&#64;").replace("\r", " ").replace("\n", " ")


def render(review: dict[str, Any]) -> str:
    lines = ["# Monthly upstream review", "", f"- Fixed upstream: {UPSTREAM_URL}",
             f"- Reviewed baseline (not automatically advanced): `{review['baseline']}`",
             f"- Observed head: `{review['upstream_head']}`", f"- Local base: `{review['base_head']}`",
             "- Only clean, allowlisted fix candidates were attempted. No code from upstream was executed.",
             "- Feature/build/CI/version/merge/invariant changes remain human decisions. No automatic merge or release.",
             "- Run the project's full regression/release gates before manually approving any candidate.", "",
             "| SHA | Title | Paths | Classification | Application | Reason |", "|---|---|---|---|---|---|"]
    for item in review["items"]:
        paths = "; ".join(markdown(path) for path in item["paths"])
        lines.append(f"| `{item['sha']}` | {markdown(item['title'])} | {paths} | {item['category']} | {item['status']} | {markdown(item['reason'])} |")
        if item.get("evidence"):
            lines.extend(["", f"Failure evidence for `{item['sha']}`:", "", "<pre>" + html.escape(printable(item["evidence"])) + "</pre>", ""])
    if not review["items"]:
        lines.extend(["", "No upstream changes since the reviewed baseline; no branch or PR needed."])
    return "\n".join(lines) + "\n"


def load_state(root: Path) -> dict[str, Any]:
    value = json.loads((root / STATE_PATH).read_text(encoding="utf-8"))
    if not isinstance(value, dict) or value.get("schema") != 1:
        raise ReviewError("Unsupported upstream state schema")
    validate_sha(value.get("reviewed_upstream", ""))
    return value


def write_metadata(git: Git, review: dict[str, Any], state: dict[str, Any], branch: str) -> None:
    require_review_branch(git, branch)
    updated = dict(state, last_review=review)
    contents = ((STATE_PATH, json.dumps(updated, ensure_ascii=True, indent=2) + "\n"),
                (LEDGER_PATH, render(review)))
    # Validate both destinations before changing either tracked file.
    for relative, _ in contents:
        path = git.root / relative
        if path.resolve() != path.absolute():
            raise ReviewError("Review metadata path is symlinked")
    for relative, content in contents:
        path = git.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    git.run("add", "--", STATE_PATH, LEDGER_PATH)
    git.run("commit", "-m", f"chore: record {branch} review decisions")


def publish(git: Git, branch: str, base: str, report: Path, repository: str) -> str:
    require_review_branch(git, branch)
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ReviewError("Invalid GitHub repository identity")
    remote = git.run("ls-remote", "--exit-code", "--heads", "origin", f"refs/heads/{branch}", check=False)
    if remote.returncode != 2:
        raise ReviewError("Publish requires a still-absent remote monthly branch: " + evidence(remote))
    # Empty expected old oid is an atomic create-only guard against concurrent branch creation.
    git.run("push", f"--force-with-lease=refs/heads/{branch}:", "origin", f"HEAD:refs/heads/{branch}")
    result = subprocess.run(["gh", "pr", "create", "--repo", repository, "--base", base,
                             "--head", branch, "--draft", "--title", f"Monthly upstream review {branch[-7:]}",
                             "--body-file", str(report)], cwd=git.root, text=True, capture_output=True, timeout=90)
    if result.returncode:
        raise ReviewError(f"Branch pushed but PR creation FAILED (permissions/settings may block Actions PRs): {evidence(result)}")
    url = result.stdout.strip()
    if not re.fullmatch(r"https://github\.com/[^/\s]+/[^/\s]+/pull/\d+", url):
        raise ReviewError(f"PR creation returned no verifiable URL: {url[:1000]}")
    return url


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--month", default=dt.datetime.now(dt.timezone.utc).strftime("%Y-%m"))
    parser.add_argument("--base", default="master")
    parser.add_argument("--output-dir", type=Path, default=None)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    output = (args.output_dir or Path(tempfile.mkdtemp(prefix="upstream-review-"))).resolve()
    root = args.repo.resolve()
    if output == root or root in output.parents:
        parser.error("output-dir must be outside the repository to preserve a clean worktree")
    output.mkdir(parents=True, exist_ok=True)
    review: dict[str, Any] | None = None
    status: dict[str, Any] = {"status": "failed", "pr_url": None}
    try:
        branch_name(args.month)
        if args.publish and not args.apply:
            raise ReviewError("Publishing requires --apply")
        git = Git(root)
        git.clean()
        state = load_state(root)
        git.run("fetch", "--no-tags", UPSTREAM_URL, "HEAD")
        head = validate_sha(git.text("rev-parse", "FETCH_HEAD^{commit}"))
        review = plan(git, state["reviewed_upstream"], head)
        review["month"] = args.month
        clean_picks = True
        if args.apply and review["items"]:
            branch = create_branch(git, args.month, args.base)
            clean_picks = apply_plan(git, review, branch)
            write_metadata(git, review, state, branch)
        report = output / "review.md"
        report.write_text(render(review), encoding="utf-8")
        if args.publish and review["items"]:
            status["pr_url"] = publish(git, branch, args.base, report, os.environ.get("GITHUB_REPOSITORY", ""))
        status["status"] = "complete" if clean_picks else "conflict"
        return 0 if clean_picks else 2
    except (ReviewError, subprocess.SubprocessError, OSError, ValueError) as error:
        status["error"] = str(error)
        # Keep untrusted git diagnostics on one quoted line, not as Actions workflow commands.
        print("upstream review FAILED: " + json.dumps(str(error), ensure_ascii=True), flush=True)
        return 1
    finally:
        if review is not None:
            (output / "review.json").write_text(json.dumps(review, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
            (output / "review.md").write_text(render(review), encoding="utf-8")
        (output / "status.json").write_text(json.dumps(status, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
        if "error" in status:
            with (output / "review.md").open("a", encoding="utf-8") as report:
                report.write("\n## FAILED\n\n<pre>" + html.escape(printable(status["error"])) + "</pre>\n")


if __name__ == "__main__":
    raise SystemExit(main())
