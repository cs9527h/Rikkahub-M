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
  if [[ ${1:-} == com.rikkahub.mate ]]; then
    # Record ActivityManager's cold-launch metric under the same VM configuration.
    # Each iteration stops the process while keeping its disk caches and user data.
    for sample in {1..7}; do
      adb shell am force-stop "$package"
      adb shell am start -W -n "$package/me.rerere.rikkahub.RouteActivity" > "evidence/$label-cold-$sample.txt"
      grep -Eq 'TotalTime: [0-9]+' "evidence/$label-cold-$sample.txt"
      sleep 1
    done
  fi
}
adb install -g baseline.apk
capture com.rikkahub.mate baseline
if [[ ${1:-} == signed ]]; then
  adb shell pm list packages -U com.rikkahub.mate > evidence/uid-before.txt
  adb install -r "dist/Rikkahub-M-${VERSION}.apk"
  adb shell dumpsys package com.rikkahub.mate > evidence/package-after.txt
  grep -F "versionName=$VERSION" evidence/package-after.txt
  adb shell pm list packages -U com.rikkahub.mate > evidence/uid-after.txt
  diff evidence/uid-before.txt evidence/uid-after.txt
  capture com.rikkahub.mate candidate
  exit 0
fi
shopt -s nullglob
apks=(candidate/*.apk)
test "${#apks[@]}" -eq 1
adb install -g "${apks[0]}"
capture com.rikkahub.mate.debug candidate
