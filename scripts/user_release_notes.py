#!/usr/bin/env python3
"""把 app/src/main/assets/updates.json 里的用户版说明生成 GitHub Release 正文。

GitHub Release 的技术细节（发布策略、Actions、JVM 测试、Lint、SHA-256）
保存在 docs/releases/<version>.md，不再作为 Release 正文，也不整段进入应用。
用法：python3 scripts/user_release_notes.py 0.6.1 > /tmp/notes.md
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

CATEGORIES = ("新增", "优化", "修复", "其他")
UPDATES = pathlib.Path("app/src/main/assets/updates.json")
HEADING = re.compile(r"^#{2,4}\s*(新增|优化|修复|其他)\s*$")
BOLD = re.compile(r"^\*\*(新增|优化|修复|其他)\*\*\s*[：:]?\s*$")
PLAIN = re.compile(r"^(新增|优化|修复|其他)\s*[：:]\s*$")
BULLET = re.compile(r"^(?:[-*•]|\d+[.、])\s+(.*\S)$")


def categories(body: str) -> list[tuple[str, list[str]]]:
    buckets: dict[str, list[str]] = {}
    current: str | None = None
    for raw in body.splitlines():
        line = raw.strip()
        match = HEADING.match(line) or BOLD.match(line) or PLAIN.match(line)
        if match:
            current = match.group(1)
            buckets.setdefault(current, [])
            continue
        if line.startswith("#"):
            current = None
            continue
        if not line or line.startswith(">"):
            continue
        item = BULLET.match(line)
        text = item.group(1).strip() if item else line
        if current and text:
            buckets.setdefault(current, []).append(text)
    ordered = []
    for name in CATEGORIES:
        items = buckets.get(name) or []
        if items:
            ordered.append((name, items))
    return ordered


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: user_release_notes.py <version>", file=sys.stderr)
        return 2
    version = sys.argv[1]
    data = json.loads(UPDATES.read_text(encoding="utf-8"))
    note = next((r for r in data.get("releases", []) if r.get("version") == version), None)
    if note is None:
        print(f"updates.json 缺少 {version} 的用户版说明", file=sys.stderr)
        return 1
    summary = (note.get("summary") or "").strip()
    sections = categories(note.get("body") or "")
    if not sections:
        print(f"{version} 的用户版说明没有可显示的分类内容", file=sys.stderr)
        return 1
    lines: list[str] = [f"Rikkahub-M {version} 正式版。", ""]
    if summary:
        lines += [f"**{summary}**", ""]
    for name, items in sections:
        lines += [f"### {name}", ""]
        lines += [f"- {item}" for item in items]
        lines.append("")
    english = pathlib.Path("docs/releases") / f"{version}.en.md"
    if english.is_file():
        lines += english.read_text(encoding="utf-8").strip().splitlines()
        lines.append("")
    lines += [
        "---",
        "",
        f"包名 `com.rikkahub.mate`，签名与旧版一致，可直接覆盖安装。",
        f"技术细节（校验项与构建记录）见仓库 `docs/releases/{version}.md`。",
        "",
    ]
    sys.stdout.write("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
