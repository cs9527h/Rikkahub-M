#!/usr/bin/env python3
"""APK 产物一致性门禁。

用途：CI 里拿到构建出来的 APK 之后，确认它**真的是这一版**，而不是缓存命中的旧包
或者空壳包。检查项：

1. APK 是合法 zip，含 android: 清单（AndroidManifest.xml）与至少一个非空 classes*.dex；
2. dex 里能找到期望的 versionName 字符串（BuildConfig 常量会被内联进 dex）；
3. dex 里能找到期望的 versionCode 十进制字符串；
4. `--require-marker` 指定的标记字符串都在（例如 `history_memory_task_log` 这种
   版本独有符号）；
5. `--forbid-marker` 指定的字符串一个都不在（例如已删除的旧符号）。

只读、不联网、不依赖第三方库；失败时按退出码区分：
  0 = 通过，1 = 校验不通过，2 = 输入/用法问题。

示例：
  python3 scripts/verify_apk_artifact.py --apk build/app.apk \\
      --version-name 0.8.6 --version-code 32 \\
      --require-marker history_memory_task_log
"""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Sequence

# 单个 dex 的搜索上限：正常 dex 几 MB，20MB 足够，避免极端文件把内存吃光
MAX_DEX_BYTES = 20 * 1024 * 1024

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_USAGE = 2


@dataclass
class ArtifactReport:
    apk: str
    ok: bool = True
    dex_entries: list[str] = field(default_factory=list)
    failures: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)

    def fail(self, message: str) -> None:
        self.ok = False
        self.failures.append(message)

    def note(self, message: str) -> None:
        self.notes.append(message)

    def as_dict(self) -> dict:
        return {
            "apk": self.apk,
            "ok": self.ok,
            "dex_entries": self.dex_entries,
            "failures": self.failures,
            "notes": self.notes,
        }


def _dex_entries(archive: zipfile.ZipFile) -> list[str]:
    return sorted(
        name
        for name in archive.namelist()
        if name.startswith("classes") and name.endswith(".dex") and "/" not in name
    )


def _read_limited(archive: zipfile.ZipFile, name: str) -> bytes:
    with archive.open(name) as handle:
        return handle.read(MAX_DEX_BYTES)


def verify_apk(
    apk_path: Path,
    version_name: str | None = None,
    version_code: int | str | None = None,
    require_markers: Sequence[str] = (),
    forbid_markers: Sequence[str] = (),
    min_dex_entries: int = 1,
) -> ArtifactReport:
    """校验一个 APK 产物；返回报告，不抛异常（输入不存在时由调用方判断）。"""
    report = ArtifactReport(apk=str(apk_path))

    if not apk_path.is_file():
        report.fail(f"APK 不存在：{apk_path}")
        return report

    try:
        archive = zipfile.ZipFile(apk_path)
    except (zipfile.BadZipFile, OSError) as error:
        report.fail(f"不是合法的 APK/ZIP：{error}")
        return report

    with archive:
        names = set(archive.namelist())
        if "AndroidManifest.xml" not in names:
            report.fail("APK 内缺少 AndroidManifest.xml")
        dex_entries = _dex_entries(archive)
        report.dex_entries = dex_entries
        if len(dex_entries) < min_dex_entries:
            report.fail(f"classes*.dex 数量不足：{len(dex_entries)} < {min_dex_entries}")

        blobs: list[bytes] = []
        for entry in dex_entries:
            try:
                data = _read_limited(archive, entry)
            except (KeyError, OSError, zipfile.BadZipFile) as error:
                report.fail(f"读取 {entry} 失败：{error}")
                continue
            if len(data) <= 8:
                # dex 头本身就有 112 字节；过小说明是空壳/占位文件
                report.fail(f"{entry} 内容为空或损坏（{len(data)} 字节）")
                continue
            blobs.append(data)

        haystack = b"\x00".join(blobs)

        if version_name:
            marker = version_name.encode("utf-8")
            if marker not in haystack:
                report.fail(f"dex 里找不到 versionName 字符串：{version_name}")
            else:
                report.note(f"versionName 命中：{version_name}")

        if version_code is not None:
            marker = str(version_code).encode("utf-8")
            if marker not in haystack:
                report.fail(f"dex 里找不到 versionCode 字符串：{version_code}")
            else:
                report.note(f"versionCode 命中：{version_code}")

        for marker in require_markers:
            if marker.encode("utf-8") not in haystack:
                report.fail(f"缺少必需标记字符串：{marker}")
            else:
                report.note(f"标记命中：{marker}")

        for marker in forbid_markers:
            if marker.encode("utf-8") in haystack:
                report.fail(f"出现应当已删除的标记字符串：{marker}")
            else:
                report.note(f"已确认移除：{marker}")

    return report


def _parse_args(argv: Iterable[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="校验 APK 产物与版本信息是否一致")
    parser.add_argument("--apk", required=True, help="待校验的 APK 路径")
    parser.add_argument("--version-name", help="期望的 versionName，例如 0.8.6")
    parser.add_argument("--version-code", type=int, help="期望的 versionCode，例如 32")
    parser.add_argument(
        "--require-marker",
        action="append",
        default=[],
        help="必须出现在 dex 里的标记字符串，可重复",
    )
    parser.add_argument(
        "--forbid-marker",
        action="append",
        default=[],
        help="不允许出现在 dex 里的标记字符串，可重复",
    )
    parser.add_argument("--min-dex-entries", type=int, default=1, help="最少 classes*.dex 个数")
    parser.add_argument("--json", action="store_true", help="以 JSON 输出结论")
    return parser.parse_args(list(argv) if argv is not None else None)


def main(argv: Iterable[str] | None = None) -> int:
    args = _parse_args(argv)
    apk = Path(args.apk)
    if not apk.is_file():
        # 输入问题单独一个退出码，方便 CI 与「校验不通过」区分
        message = f"APK 不存在：{apk}"
        if args.json:
            print(json.dumps({"apk": str(apk), "ok": False, "failures": [message]}, ensure_ascii=False))
        else:
            print(message)
        return EXIT_USAGE

    try:
        report = verify_apk(
            apk,
            version_name=args.version_name,
            version_code=args.version_code,
            require_markers=args.require_marker,
            forbid_markers=args.forbid_marker,
            min_dex_entries=args.min_dex_entries,
        )
    except Exception as error:  # 用法/环境问题，不伪装成校验结论
        if args.json:
            print(json.dumps({"apk": str(apk), "ok": False, "failures": [str(error)]}, ensure_ascii=False))
        else:
            print(f"校验过程失败：{error}")
        return EXIT_USAGE

    if args.json:
        print(json.dumps(report.as_dict(), ensure_ascii=False, indent=2))
    else:
        print(f"APK: {report.apk}")
        print(f"classes*.dex: {', '.join(report.dex_entries) or '无'}")
        for note in report.notes:
            print(f"  ok   {note}")
        for failure in report.failures:
            print(f"  FAIL {failure}")
        print("结论：" + ("通过" if report.ok else "不通过"))

    return EXIT_OK if report.ok else EXIT_FAILED


if __name__ == "__main__":
    sys.exit(main())
