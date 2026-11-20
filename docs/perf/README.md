# 性能基线

每个版本都要回答一个问题：这一版比上一个已验收的稳定版慢了吗？

## 两类数据

| 数据 | 采集方式 | 用途 |
|---|---|---|
| 模拟器冷启动（CI `perf-benchmark`） | `:app:baselineprofile:connectedBenchmarkReleaseAndroidTest` | 趋势参考。共享宿主、无真实磁盘，波动大，**不能当作验收结论** |
| 真机冷启动、滚动、流式帧稳定性、内存峰值 | 同一型号安卓设备 + `benchmarkRelease` + 不充电、固定亮度 | 唯一可写入 `baseline.json` 的验收数据 |

## 门禁

- `scripts/perf_compare.py` 读取 `baseline.json`，任一指标回退超过 `thresholdPercent`（默认 10%）即失败。
- 基线未采集时脚本只提示跳过，并在发布说明里标注“性能待真机验收”，不得写成通过。
- `release.yml` 校验 `docs/perf/baseline.json` 的 `version` 与发布版本一致，强制每版记录性能状态。

## 采集真机基线

1. 手机安装 `benchmarkRelease` 变体；
2. `./gradlew :app:baselineprofile:connectedBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.baselineprofile.StartupBenchmarks`；
3. 把输出 JSON 的中位数填进 `baseline.json` 的 `metrics`，`measured` 改 `true`，写清设备型号与系统版本；
4. 之后每个版本都跑同一场景，与这份文件比。
