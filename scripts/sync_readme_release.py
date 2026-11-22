#!/usr/bin/env python3
"""把最新正式 Release 同步进各语言 README 的自动维护区块。

只认「已发布的正式版」（draft=false, prerelease=false），且必须同时具备
APK 与 SHA256SUMS 资产；任何一项缺失或 API 失败都保持上次内容不变。
"""
from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BEGIN = "<!-- RELEASE-BLOCK:BEGIN -->"
END = "<!-- RELEASE-BLOCK:END -->"
TARGETS = ("README.md", "README_ZH_CN.md", "README_EN.md", "README_ZH_TW.md")


def api(url: str, token: str) -> dict:
    request = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "User-Agent": "Rikkahub-M-readme-sync",
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def latest_stable(repo: str, token: str) -> dict | None:
    releases = api(f"https://api.github.com/repos/{repo}/releases?per_page=20", token)
    for release in releases:
        if release.get("draft") or release.get("prerelease"):
            continue
        assets = {a["name"]: a for a in release.get("assets", [])}
        apk = next((name for name in assets if name.endswith(".apk")), None)
        sums = "SHA256SUMS.txt" if "SHA256SUMS.txt" in assets else None
        if not apk or not sums:
            continue
        if assets[apk].get("size", 0) <= 0 or assets[sums].get("size", 0) <= 0:
            continue
        release["_apk"] = apk
        release["_apk_size"] = assets[apk]["size"]
        release["_apk_url"] = assets[apk]["browser_download_url"]
        return release
    return None


def sha256_for(release: dict, repo: str, token: str) -> str | None:
    sums_url = next(
        a["browser_download_url"] for a in release["assets"] if a["name"] == "SHA256SUMS.txt"
    )
    request = urllib.request.Request(sums_url, headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(request, timeout=30) as response:
        text = response.read().decode("utf-8")
    match = re.search(r"^([0-9a-fA-F]{64})\s+\*?" + re.escape(release["_apk"]) + r"$", text, re.M)
    return match[1].lower() if match else None


def bundled_note(version: str) -> dict | None:
    path = ROOT / "app/src/main/assets/updates.json"
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    for note in data.get("releases", []):
        if note.get("version") == version:
            return note
    return None


def bullets(body: str, limit: int = 2) -> list[str]:
    lines = []
    for raw in body.splitlines():
        line = raw.strip()
        if line.startswith("- ") or line.startswith("* "):
            item = line[2:].replace("**", "").strip()
            if item:
                lines.append(item)
        if len(lines) >= limit:
            break
    return lines


def size_label(size: int) -> str:
    return f"{size / (1024 * 1024):.1f} MB"


def block(release: dict, sha: str, lang: str) -> str:
    version = release["tag_name"].lstrip("v")
    published = (release.get("published_at") or "")[:10]
    code = release.get("target_commitish", "")
    notes = f"docs/releases/{version}.md"
    # 优先用内置日志里的手写摘要与要点；Release 正文偏技术，不适合当首页摘要
    note = bundled_note(version) or {}
    summary_text = (note.get("summary") or "").strip()
    bullets_text = bullets(note.get("body") or "") or bullets(release.get("body") or "")

    if lang == "zh":
        summary = summary_text or ("；".join(bullets_text) if bullets_text else "详见完整更新日志。")
        return (
            f"{BEGIN}\n"
            f"> **最新正式版：v{version}**（{published}，commit `{code[:8]}`）\n>\n"
            f"> 安装包：[{release['_apk']}]({release['_apk_url']}) · {size_label(release['_apk_size'])} · universal（arm64-v8a + x86_64）\n>\n"
            f"> SHA-256：`{sha}`\n>\n"
            f"> 本版摘要：{summary}\n>\n"
            f"> 完整说明见 [v{version} 更新日志]({notes})。\n"
            f"{END}"
        )
    if lang == "zh-TW":
        summary = summary_text or ("；".join(bullets_text) if bullets_text else "詳見完整更新日誌。")
        return (
            f"{BEGIN}\n"
            f"> **最新正式版：v{version}**（{published}，commit `{code[:8]}`）\n>\n"
            f"> 安裝包：[{release['_apk']}]({release['_apk_url']}) · {size_label(release['_apk_size'])} · universal（arm64-v8a + x86_64）\n>\n"
            f"> SHA-256：`{sha}`\n>\n"
            f"> 本版摘要：{summary}\n>\n"
            f"> 完整說明見 [v{version} 更新日誌]({notes})。\n"
            f"{END}"
        )
    return (
        f"{BEGIN}\n"
        f"> **Latest stable release: v{version}** ({published}, commit `{code[:8]}`)\n>\n"
        f"> APK: [{release['_apk']}]({release['_apk_url']}) · {size_label(release['_apk_size'])} · universal (arm64-v8a + x86_64)\n>\n"
        f"> SHA-256: `{sha}`\n>\n"
        f"> Summary: see the release notes for this version.\n>\n"
        f"> Details: [v{version} release notes]({notes}).\n"
        f"{END}"
    )


def replace_block(text: str, new_block: str) -> str:
    pattern = re.compile(re.escape(BEGIN) + r".*?" + re.escape(END), re.S)
    if not pattern.search(text):
        raise SystemExit("README 缺少 RELEASE-BLOCK 标记，拒绝改写")
    return pattern.sub(new_block, text, count=1)


def main() -> int:
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY", "cs9527h/Rikkahub-M")
    if not token:
        print("缺少 GH_TOKEN，保留现有内容", file=sys.stderr)
        return 0
    try:
        release = latest_stable(repo, token)
        if release is None:
            print("没有找到含 APK 的正式 Release，保留现有内容")
            return 0
        sha = sha256_for(release, repo, token)
        if not sha:
            print("SHA256SUMS 无法核对 APK 哈希，保留现有内容", file=sys.stderr)
            return 0
    except (urllib.error.URLError, TimeoutError, ValueError) as error:
        print(f"读取 Release 失败，保留现有内容：{error}", file=sys.stderr)
        return 0

    changed = []
    for name, lang in (("README.md", "zh"), ("README_ZH_CN.md", "zh"),
                       ("README_EN.md", "en"), ("README_ZH_TW.md", "zh-TW")):
        path = ROOT / name
        if not path.exists():
            continue
        original = path.read_text(encoding="utf-8")
        updated = replace_block(original, block(release, sha, lang))
        if updated != original:
            path.write_text(updated, encoding="utf-8")
            changed.append(name)
    print("updated: " + (", ".join(changed) if changed else "none"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
