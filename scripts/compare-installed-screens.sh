#!/usr/bin/env bash
# Installs the shipped 0.2.4 build and the candidate build on the same emulator,
# captures both chat screens plus cold-start timings, and leaves evidence for review.
# Missing windows are reported as warnings: the artifact (screens + logcat) is the result.
set -uo pipefail
mkdir -p evidence

capture() {
  local package=$1 label=$2 theme
  for theme in no yes; do
    adb shell cmd uimode night "$theme" >/dev/null 2>&1 || true
    adb shell am force-stop "$package" >/dev/null 2>&1 || true
    adb shell am start -W -n "$package/me.rerere.rikkahub.RouteActivity" > "evidence/$label-$theme-start.txt" 2>&1 || true
    # uiautomator can return a stale dump; retry until the app's own window shows up.
    local ready=0
    for attempt in {1..15}; do
      adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || { sleep 2; continue; }
      adb pull /sdcard/window.xml "evidence/$label-$theme.xml" >/dev/null 2>&1 || { sleep 2; continue; }
      if grep -Fq "package=\"$package\"" "evidence/$label-$theme.xml"; then ready=1; break; fi
      sleep 2
    done
    adb exec-out screencap -p > "evidence/$label-$theme.png" 2>/dev/null || true
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
      echo "WARN: $label($theme) window not confirmed; see logcat"
    fi
  done
  for sample in {1..5}; do
    adb shell am force-stop "$package" >/dev/null 2>&1 || true
    adb shell am start -W -n "$package/me.rerere.rikkahub.RouteActivity" > "evidence/$label-cold-$sample.txt" 2>&1 || true
    sleep 1
  done
}

adb install -g baseline.apk || { echo "baseline install failed"; exit 1; }
capture com.rikkahub.mate baseline

shopt -s nullglob
apks=(candidate/*.apk)
if [ "${#apks[@]}" -ne 1 ]; then
  echo "WARN: expected one candidate apk, found ${#apks[@]}"
  exit 1
fi
adb install -r "${apks[0]}" || { echo "candidate install failed"; exit 1; }
capture com.rikkahub.mate.debug candidate
echo "evidence written to $(pwd)/evidence"

python3 - <<'PY'
import pathlib, re, statistics
for label in ("baseline", "candidate"):
    values = []
    for path in sorted(pathlib.Path("evidence").glob(label + "-cold-*.txt")):
        match = re.search(r"^TotalTime:\s*(\d+)", path.read_text(), re.M)
        if match:
            values.append(int(match.group(1)))
    print(label, "cold start TotalTime ms", values, "median", statistics.median(values) if values else "unavailable")
PY
