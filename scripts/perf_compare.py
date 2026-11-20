#!/usr/bin/env python3
"""对比 Macrobenchmark 结果与 docs/perf/baseline.json，超过阈值即失败。

- measured=true 的真机基线是验收依据；
- ciEmulator 记录同一类模拟器的趋势值，仅用于发现明显回退，不作为发布验收。
用法：python3 scripts/perf_compare.py [benchmarkData.json ...]
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
            if not isinstance(bench, dict):
                continue
            name = str(bench.get("name", ""))
            metrics = bench.get("metrics", {})
            if isinstance(metrics, dict):
                entries = metrics.items()
            elif isinstance(metrics, list):
                entries = ((m.get("name", ""), m) for m in metrics)
            else:
                continue
            for metric_name, metric in entries:
                if not isinstance(metric, dict):
                    continue
                value = metric.get("median")
                if isinstance(value, (int, float)):
                    found[f"{name}.{metric_name}"] = float(value)
    return found


def compare(label, baseline_metrics, current, threshold):
    failures = []
    for key, base in baseline_metrics.items():
        if key not in current:
            print(f"[{label}] 缺少指标 {key}，跳过")
            continue
        delta = (current[key] - float(base)) / float(base) * 100 if base else 0
        print(f"[{label}] {key}: {float(base):.1f} -> {current[key]:.1f} ({delta:+.1f}%)")
        if delta > threshold:
            failures.append(f"{key} 回退 {delta:.1f}%")
    return failures


def main(argv):
    baseline = json.loads(BASELINE.read_text())
    threshold = float(baseline.get("thresholdPercent", 10))
    inputs = argv[1:] or glob.glob(str(ROOT / "**" / "*benchmarkData.json"), recursive=True)
    if not inputs:
        print("没有找到 benchmarkData.json，跳过对比")
        return 0
    current = load_results(inputs)
    if not current:
        print("benchmarkData.json 无法解析，跳过对比")
        return 0

    failures = []
    if baseline.get("measured") and baseline.get("metrics"):
        failures += compare("真机基线", baseline["metrics"], current, threshold)
    else:
        print("真机基线未采集：本次不能宣称性能达标")
        ci = baseline.get("ciEmulator") or {}
        if ci.get("metrics"):
            failures += compare("模拟器趋势", ci["metrics"], current, threshold)
    if failures:
        print("性能回退超阈值：" + "；".join(failures))
        return 1
    print("性能对比通过")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
