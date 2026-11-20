#!/usr/bin/env python3
"""对比 Macrobenchmark 结果与 docs/perf/baseline.json，超过阈值即失败。

用法：python3 scripts/perf_compare.py [benchmark.json ...]
没有输入文件或基线未采集时只打印提示并退出 0（不得伪造数据）。
"""
import glob
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASELINE = ROOT / "docs" / "perf" / "baseline.json"


def load_results(paths):
    found = {}
    for path in paths:
        try:
            data = json.loads(pathlib.Path(path).read_text())
        except (OSError, ValueError):
            continue
        for bench in data.get("benchmarks", []):
            name = bench.get("name") or ""
            for metric in bench.get("metrics", []):
                key = name.split("_")[-1] + "." + metric.get("name", "")
                value = metric.get("median")
                if isinstance(value, (int, float)):
                    found[key] = float(value)
    return found


def main(argv):
    baseline = json.loads(BASELINE.read_text())
    threshold = float(baseline.get("thresholdPercent", 10))
    if not baseline.get("measured") or not baseline.get("metrics"):
        print("基线未采集：本次跳过数值对比（不得据此宣称性能达标）")
        return 0
    inputs = argv[1:] or glob.glob(str(ROOT / "**" / "*benchmark*.json"), recursive=True)
    if not inputs:
        print("没有找到 benchmark JSON，跳过对比")
        return 0
    current = load_results(inputs)
    failures = []
    for key, base in baseline["metrics"].items():
        if key not in current:
            print(f"缺少指标 {key}，跳过")
            continue
        delta = (current[key] - base) / base * 100 if base else 0
        print(f"{key}: {base:.1f} -> {current[key]:.1f} ({delta:+.1f}%)")
        if delta > threshold:
            failures.append(f"{key} 回退 {delta:.1f}%")
    if failures:
        print("性能回退超阈值：" + "；".join(failures))
        return 1
    print("性能对比通过")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
