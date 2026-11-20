#!/usr/bin/env bash
set -euo pipefail
mkdir -p evidence
capture() {
  local package=$1 label=$2 theme
  for theme in no yes; do
    adb shell cmd uimode night "$theme"
    adb shell am force-stop "$package"
    adb shell am start -W -n "$package/me.rerere.rikkahub.RouteActivity" > "evidence/$label-$theme-start.txt"
    # Wait for the application window and an idle accessibility hierarchy.
    for attempt in {1..10}; do
      adb shell uiautomator dump /sdcard/window.xml >/dev/null || continue
      adb pull /sdcard/window.xml "evidence/$label-$theme.xml" >/dev/null
      if grep -Fq "package=\"$package\"" "evidence/$label-$theme.xml"; then break; fi
      sleep 1
    done
    grep -Fq "package=\"$package\"" "evidence/$label-$theme.xml"
    adb exec-out screencap -p > "evidence/$label-$theme.png"
  done
}
adb install -g baseline.apk
capture com.rikkahub.mate baseline
shopt -s nullglob
apks=(candidate/*.apk)
test "${#apks[@]}" -eq 1
adb install -g "${apks[0]}"
capture com.rikkahub.mate.debug candidate
