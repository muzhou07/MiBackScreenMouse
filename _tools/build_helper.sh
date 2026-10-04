#!/bin/bash
# 交叉编译 root 助手为 libbsm_helper.so（放进 jniLibs，随 APK 打包）
set -euo pipefail
SRC=/mnt/d/Code/VSCode/Android/MiBackScreenMouse
NDK=/home/muzhou/android-sdk/ndk/29.0.14206865
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang
OUT=$SRC/app/src/main/jniLibs/arm64-v8a

mkdir -p "$OUT"
"$CC" -O2 -std=c11 -Wall -Wextra -Wno-unused-parameter -Wno-unused-result \
    "$SRC/app/src/main/cpp/bsm_helper.c" -o "$OUT/libbsm_helper.so"
echo "== built =="
ls -l "$OUT/libbsm_helper.so"
