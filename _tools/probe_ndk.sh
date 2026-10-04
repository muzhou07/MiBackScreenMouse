#!/bin/bash
echo "== ndk dirs =="
ls -1 /home/muzhou/android-sdk/ndk 2>/dev/null
echo "== ndk revisions =="
for d in /home/muzhou/android-sdk/ndk/*/; do
  rev=$(grep -h -m1 'Pkg.Revision' "$d/source.properties" 2>/dev/null)
  echo "$d -> ${rev:-MISSING source.properties}"
done
echo "== clang =="
ls -1 /home/muzhou/android-sdk/ndk/*/toolchains/llvm/prebuilt/*/bin/clang 2>/dev/null
echo "== cmake =="
ls -1 /home/muzhou/android-sdk/cmake 2>/dev/null || echo "no cmake in sdk"
ls -1 /home/muzhou/android-sdk/build-tools/37.0.0 2>/dev/null | head -20
