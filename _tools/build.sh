#!/bin/bash
# 在 WSL 内用已缓存的 Gradle 9.3.1 构建本工程
set -euo pipefail
PROJ=/mnt/d/Code/VSCode/Android/MiBackScreenMouse
GRADLE=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-9.3.1-bin/*/gradle-9.3.1/bin/gradle | head -1)
echo "gradle: $GRADLE"
cd "$PROJ"
exec "$GRADLE" "$@"
