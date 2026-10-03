#!/bin/bash
# 构建产物自检：确认“背屏强制接管”相关代码/清单项已彻底从 APK 中消失
set -uo pipefail

APK=/mnt/d/Code/VSCode/Android/MiBackScreenMouse/artifacts/app-debug.apk
B="$HOME/bsm"

echo "=== 合并后的 AndroidManifest 关键项 ==="
MF=$(find "$B/app/build/intermediates" -name AndroidManifest.xml -path '*merged_manifest*' | head -1)
echo "file: $MF"
grep -nE 'miui\.rear\.policy|TouchpadSessionService|FOREGROUND_SERVICE|android:exported|TouchpadActivity' "$MF" 2>/dev/null | head -20

echo
echo "=== APK 内 dex 关键字检索 ==="
tmp=$(mktemp -d)
cd "$tmp" || exit 1
unzip -qq -o "$APK" 'classes*.dex' 2>/dev/null
for d in classes*.dex; do
  printf '%s: TouchpadSessionService=%s\n' "$d" "$(strings "$d" | grep -c TouchpadSessionService)"
done
printf '残留 force-stop 字符串=%s\n' "$(strings classes*.dex | grep -c 'force-stop')"
printf '残留 --display 字符串=%s\n' "$(strings classes*.dex | grep -c -- '--display')"
printf 'settings put 字符串=%s\n' "$(strings classes*.dex | grep -c 'settings put')"
# 注意：中文日志串在 dex 里是 UTF-8，binutils 的 strings 不会输出，故不做中文字符串检索
cd / && rm -rf "$tmp"
