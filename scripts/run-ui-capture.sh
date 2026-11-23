#!/usr/bin/env bash
# 在 CI 模拟器里安装候选包并截图。
#
# 单独成文件的原因：android-emulator-runner 的 `script:` 是交给 /bin/sh 的单段脚本，
# 直接在 YAML 里写 `if [[ ... ]]` 之类 bash 语法会被 sh 拒绝（Syntax error: end of file unexpected）。
# 这里用 bash 完整执行，CI 侧只调用一行。
#
# 用法：bash scripts/run-ui-capture.sh <label> <apk path>
set -euo pipefail

label=${1:-candidate}
apk=${2:?expected apk path}
test -s "$apk"

# adb 在 runner 上刚起来时偶尔还没就绪，等一会儿再判定失败
if ! adb wait-for-device; then
  echo "ERROR: adb device not available"
  exit 1
fi
for _ in $(seq 1 10); do
  if adb shell true >/dev/null 2>&1; then break; fi
  sleep 3
done

# 包名从清单读，避免把 debug 包当正式包安装后截图空白
package=com.rikkahub.mate
analyzer=$(find "${ANDROID_HOME:-/usr/local/lib/android/sdk}" -name apkanalyzer 2>/dev/null | sort | tail -1)
if [ -n "${analyzer:-}" ] && [ -x "$analyzer" ]; then
  detected=$("$analyzer" manifest application-id "$apk" 2>/dev/null || true)
  if [ -n "${detected:-}" ]; then package=$detected; fi
fi
echo "installing $apk as $package"
adb install -r -t "$apk"

bash scripts/capture-ui-screens.sh "$label" "$package"
