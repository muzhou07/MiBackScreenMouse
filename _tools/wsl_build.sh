#!/bin/bash
# 同步工程到 WSL 原生目录 -> 构建 -> 把 APK 拉回 Windows artifacts 目录
# 构建前先删除源码侧已不存在的文件，避免残留文件继续参与编译。
set -euo pipefail

SRC=/mnt/d/Code/VSCode/Android/MiBackScreenMouse
DST="$HOME/bsm"
OUT="$SRC/artifacts"
GRADLE=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-9.3.1-bin/*/gradle-9.3.1/bin/gradle | head -1)

mkdir -p "$DST" "$OUT"

# 1) 清理残留文件（排除 gradle/build 生成物与构建生成的 jniLibs）
(cd "$DST" && find . -type f \
  -not -path './.gradle/*' -not -path './build/*' -not -path './app/build/*' \
  -not -path './.git/*' -not -path './app/src/main/jniLibs/*') | while IFS= read -r rel; do
  [ -e "$SRC/$rel" ] || rm -f "$DST/$rel"
done

# 2) 覆盖式同步（jniLibs 是本机/构建产物，不同步，改为构建时重新交叉编译）
tar -C "$SRC" -cf - \
  --exclude=./.gradle --exclude=./build --exclude=./app/build \
  --exclude=./_decomp --exclude=./_refs --exclude=./adb --exclude=./_tools \
  --exclude=./artifacts --exclude='*.apk' --exclude=./app/src/main/jniLibs \
  . | tar -C "$DST" -xf -

# 3) 丢掉上次生成的助手，强制重新交叉编译（避免陈旧 .so 被打进 APK）
rm -rf "$DST/app/src/main/jniLibs"

ARGS=("$@")
if [ ${#ARGS[@]} -eq 0 ]; then
  ARGS=(assembleDebug)
fi

cd "$DST"
echo "=== gradle ${ARGS[*]} ==="
"$GRADLE" "${ARGS[@]}" --console=plain

if ls "$DST"/app/build/outputs/apk/*/*.apk >/dev/null 2>&1 || ls "$DST"/btprobe/build/outputs/apk/*/*.apk >/dev/null 2>&1; then
  # 主 App 与测试模块的 APK 都拉回 artifacts（文件名不同，不会互相覆盖）
  cp -f "$DST"/app/build/outputs/apk/*/*.apk "$OUT"/ 2>/dev/null || true
  cp -f "$DST"/btprobe/build/outputs/apk/*/*.apk "$OUT"/ 2>/dev/null || true
  echo "=== APK ==="
  ls -l "$OUT"/*.apk 2>/dev/null || true
fi
