# 版本号规则

## 显示版本（versionName）

三段式 `主.次.修订`，每段 0–9，**逢 10 进 1**：

- `1.0.8` → `1.0.9` → `1.1.0` → `1.1.1` … → `1.9.9` → `2.0.0`
- 每次发布只把**修订位** +1；到 9 后进位到次位，次位到 9 后进位到主位。
- 不再出现 `0.9.18` 这种第三位两位数（历史版本保留原样）。

## 构建版本（versionCode）

单调递增整数，每次发布 +1，与显示版本解耦：

| 显示版本 | versionCode |
| --- | --- |
| 0.9.18 | 54 |
| 1.0.8 | 55 |

`release.yml` 里的门禁是 `version-code > 上一版`，只校验单调性，不解析显示版本，
所以切换显示版本不会影响覆盖安装。

## 发布时要同步的位置

1. `app/build.gradle.kts`：`versionCode` +1、`versionName` 按规则 +1；
2. `.github/workflows/release.yml`：`env.VERSION` 与 `version-code -gt <上一版 code>`；
3. `CHANGELOG_MATE.md`、`app/src/main/assets/updates.json`、`docs/releases/<version>.md`、`docs/perf/baseline.json`；
4. 打 tag `v<version>` 并推送，再 `gh workflow run release.yml --ref master -f publish_release=true`。
