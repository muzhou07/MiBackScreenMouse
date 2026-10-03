#!/bin/bash
# WSL 环境探针：确认可离线解析的依赖版本与工具链位置
set -u
CACHE="$HOME/.gradle/caches/modules-2/files-2.1"
for p in \
  com.android.tools.build/gradle \
  org.jetbrains.kotlin/kotlin-gradle-plugin \
  org.jetbrains.kotlin.plugin.compose/org.jetbrains.kotlin.plugin.compose.gradle.plugin \
  androidx.compose/compose-bom \
  androidx.activity/activity-compose \
  androidx.core/core-ktx \
  androidx.lifecycle/lifecycle-runtime-ktx \
  androidx.compose.material3/material3 \
  androidx.compose.ui/ui ; do
  echo "== $p =="
  ls "$CACHE/$p" 2>/dev/null | tail -6
done
echo "== gradle dist bins =="
ls -d "$HOME"/.gradle/wrapper/dists/*/*/gradle-*/bin 2>/dev/null
echo "== SDK =="
ls /home/muzhou/android-sdk/platforms /home/muzhou/android-sdk/build-tools 2>/dev/null
echo "== net (WSL) =="
curl -sS -m 12 -o /dev/null -w "google:%{http_code}\n" https://dl.google.com/android/repository/repository2-3.xml 2>&1
curl -sS -m 12 -o /dev/null -w "maven:%{http_code}\n" https://repo1.maven.org/maven2/ 2>&1
echo "== env =="
java -version 2>&1 | head -2
echo "JAVA_HOME=${JAVA_HOME:-<unset>}"
echo "ANDROID_HOME=${ANDROID_HOME:-<unset>}"
cat "$HOME/.gradle/gradle.properties" 2>/dev/null
