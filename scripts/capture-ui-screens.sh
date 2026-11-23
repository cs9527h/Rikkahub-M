#!/usr/bin/env bash
# 真机（模拟器）界面截图：空态 / 输入态 / 发送后，浅色与暗色各一组。
#
# 用途：UI 改动的视觉验收。CI 全绿、编译通过都不能说明界面好看，
# 只有真实渲染的截图可以。脚本不写入任何用户数据以外的内容，
# 也不导出 UI 文本（dump 只用来定位发送按钮坐标，用完即删）。
#
# 用法：bash scripts/capture-ui-screens.sh <label> [package]
#   label    输出目录名（如 baseline-v089 / candidate-v090）
#   package  要截图的包名，默认 debug 包
set -uo pipefail

label=${1:?expected label}
package=${2:-com.rikkahub.mate.debug}
activity="me.rerere.rikkahub.RouteActivity"
out="evidence/ui-$label"
mkdir -p "$out"

# 系统错误弹窗会抢焦点，把应用窗口挡掉
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true

shot() {
  local name=$1
  adb exec-out screencap -p > "$out/$name.png"
  # 空文件说明 screencap 失败，不要让残缺截图冒充证据
  if [ ! -s "$out/$name.png" ]; then
    echo "ERROR: $name.png is empty"
    return 1
  fi
  echo "captured $out/$name.png ($(wc -c < "$out/$name.png") bytes)"
}

# 从 uiautomator dump 里取某个 content-desc 元素的中心坐标。
# 只读取几何信息，落盘的 xml 会被删掉，不会带出界面文字。
center_of() {
  local desc=$1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  adb pull /sdcard/ui.xml "$out/dump.xml" >/dev/null 2>&1 || return 1
  python3 - "$out/dump.xml" "$desc" <<'PY'
import sys, xml.etree.ElementTree as ET
path, want = sys.argv[1], sys.argv[2]
try:
    tree = ET.parse(path)
except Exception:
    sys.exit(1)
for node in tree.iter():
    if node.get("content-desc") == want:
        box = node.get("bounds", "")
        try:
            left, top = box.split("][")[0].strip("[]").split(",")
            right, bottom = box.split("][")[1].strip("[]").split(",")
            print((int(left) + int(right)) // 2, (int(top) + int(bottom)) // 2)
            sys.exit(0)
        except Exception:
            continue
sys.exit(1)
PY
  local rc=$?
  rm -f "$out/dump.xml"
  return $rc
}

capture_theme() {
  local theme=$1
  adb shell cmd uimode night "$theme" >/dev/null 2>&1 || true
  adb shell am force-stop "$package" >/dev/null 2>&1 || true
  sleep 1
  adb shell am start -W -n "$package/$activity" > "$out/$theme-start.txt" 2>&1 || return 1
  grep -q '^Status: ok' "$out/$theme-start.txt" || { echo "ERROR: app failed to start ($theme)"; return 1; }
  sleep 3

  # 1) 空态：顶栏 + 背景 + 输入框
  shot "$theme-1-empty" || return 1

  # 2) 输入态：输入框随文字变化的高度与工具行
  adb shell input text "Hello%sRikkahub" >/dev/null 2>&1 || true
  sleep 2
  shot "$theme-2-typing" || return 1

  # 3) 发送后：用户气泡 + 无模型配置时的失败提示，用来检查消息排版
  local coords
  if coords=$(center_of "Send"); then
    adb shell input tap $coords >/dev/null 2>&1 || true
    sleep 4
    shot "$theme-3-sent" || true
  elif coords=$(center_of "发送"); then
    adb shell input tap $coords >/dev/null 2>&1 || true
    sleep 4
    shot "$theme-3-sent" || true
  else
    echo "note: send button not found in $theme, skipping sent shot"
  fi

  # 4) 新建会话菜单（侧边栏）——检查抽屉与列表密度
  adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  sleep 1
  local menu
  if menu=$(center_of "Messages"); then
    adb shell input tap $menu >/dev/null 2>&1 || true
    sleep 2
    shot "$theme-4-drawer" || true
  fi
  adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
}

status=0
capture_theme no || status=1
capture_theme yes || status=1
# 恢复系统默认（浅色），避免影响后续任务
adb shell cmd uimode night no >/dev/null 2>&1 || true
exit $status
