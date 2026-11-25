#!/usr/bin/env bash
# Installs the shipped 0.2.4 build and the candidate build on the same emulator,
# captures both app windows plus cold-start timings, and leaves evidence for review.
# Signed mode additionally verifies same-package data preservation; preview is comparison only.
set -euo pipefail
mode=${1:-preview}
case "$mode" in
  preview) candidate_dir=candidate; candidate_package=com.rikkahub.mate.debug ;;
  signed) candidate_dir=dist; candidate_package=com.rikkahub.mate ;;
  *) echo "ERROR: expected preview or signed mode"; exit 2 ;;
esac
mkdir -p evidence
# Emulator system ANR/crash dialogs steal focus and hide the app window from uiautomator.
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true

capture() {
  local package=$1 label=$2 theme
  for theme in no yes; do
    adb shell cmd uimode night "$theme" >/dev/null 2>&1 || true
    adb shell am force-stop "$package" >/dev/null 2>&1 || true
    adb shell am start -W -n "$package/me.rerere.rikkahub.RouteActivity" > "evidence/$label-$theme-start.txt" 2>&1 || return 1
    grep -q '^Status: ok' "evidence/$label-$theme-start.txt" || { echo "ERROR: $label failed to start"; return 1; }
    # uiautomator can return a stale dump; retry until the app's own window shows up.
    local ready=0
    for attempt in {1..15}; do
      adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || { sleep 2; continue; }
      adb pull /sdcard/window.xml "evidence/$label-$theme.xml" >/dev/null 2>&1 || { sleep 2; continue; }
      if grep -Fq "package=\"$package\"" "evidence/$label-$theme.xml"; then ready=1; break; fi
      # A system dialog (launcher ANR) may cover the app; the activity is still resumed.
      if adb shell dumpsys window 2>/dev/null | grep -Fq "mCurrentFocus=.*$package/"; then ready=1; break; fi
      # 慢速 runner 上旧版冷启动会弹系统 ANR 对话框，它抢焦点、挡住应用窗口。
      # 点掉「等待」再重试；只是让采集不被系统弹窗挡住，判定标准不变。
      if grep -Fq 'aerr_wait' "evidence/$label-$theme.xml"; then
        tap=$(python3 -c '
import re, sys, xml.etree.ElementTree as ET
for node in ET.parse(sys.argv[1]).iter():
    if node.get("resource-id") == "android:id/aerr_wait":
        match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if match:
            x1, y1, x2, y2 = map(int, match.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
' "evidence/$label-$theme.xml" 2>/dev/null || true)
        if [ -n "${tap:-}" ]; then
          # shellcheck disable=SC2086
          adb shell input tap $tap >/dev/null 2>&1 || true
          sleep 3
          continue
        fi
      fi
      sleep 2
    done
    adb exec-out screencap -p > "evidence/$label-$theme.png"
    test -s "evidence/$label-$theme.png"
    adb shell dumpsys activity activities > "evidence/$label-$theme-activity.txt" 2>&1 || true
    # Retain timing and UI geometry without exporting arbitrary log messages or UI text.
    python3 - "evidence/$label-$theme.xml" <<'PY'
import pathlib, sys, xml.etree.ElementTree as ET
path = pathlib.Path(sys.argv[1])
if path.exists():
    raw = path.read_bytes()
    path.unlink()
    tree = ET.fromstring(raw)
    for node in tree.iter():
        for key in ("text", "content-desc", "hint"):
            node.attrib.pop(key, None)
    ET.ElementTree(tree).write(path, encoding="utf-8")
PY
    if [ "$ready" -ne 1 ]; then
      echo "ERROR: $label($theme) window not confirmed; see startup evidence"
      return 1
    fi
  done
  for sample in {1..5}; do
    adb shell am force-stop "$package" >/dev/null 2>&1 || true
    adb shell am start -W -n "$package/me.rerere.rikkahub.RouteActivity" > "evidence/$label-cold-$sample.txt" 2>&1 || return 1
    grep -q '^Status: ok' "evidence/$label-cold-$sample.txt" || { echo "ERROR: $label cold start failed"; return 1; }
    sleep 1
  done
}

shopt -s nullglob
apks=("$candidate_dir"/*.apk)
if [ "${#apks[@]}" -ne 1 ]; then
  echo "ERROR: expected one candidate apk in $candidate_dir, found ${#apks[@]}"
  exit 1
fi
test -s baseline.apk || { echo "ERROR: pinned baseline APK is missing"; exit 1; }
adb install -g baseline.apk || { echo "baseline install failed"; exit 1; }
capture com.rikkahub.mate baseline
if [ "$mode" = signed ]; then
  python3 scripts/upgrade_data_contract.py prepare
else
  echo "PREVIEW_COMPARE_ONLY: different package; this is NOT an in-place upgrade test"
fi
adb install -r "${apks[0]}" || { echo "candidate install failed"; exit 1; }
capture "$candidate_package" candidate
if [ "$mode" = signed ]; then
  # 版本号从构建脚本读取，避免每次改版本都要手改这里的常量。
  expected_code=$(sed -n 's/.*versionCode *= *\([0-9][0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
  test -n "$expected_code" || { echo "ERROR: cannot read versionCode from app/build.gradle.kts"; exit 1; }
  echo "expected version code: $expected_code"
  python3 scripts/upgrade_data_contract.py verify --version-code "$expected_code"
fi
echo "evidence written to $(pwd)/evidence"

python3 - <<'PY'
import pathlib, re, statistics
for label in ("baseline", "candidate"):
    values = []
    for path in sorted(pathlib.Path("evidence").glob(label + "-cold-*.txt")):
        match = re.search(r"^TotalTime:\s*(\d+)", path.read_text(), re.M)
        if match:
            values.append(int(match.group(1)))
    if len(values) != 5 or any(value <= 0 for value in values):
        raise SystemExit(label + " lacks five valid cold-start measurements")
    print(label, "cold start TotalTime ms", values, "median", statistics.median(values))
PY
