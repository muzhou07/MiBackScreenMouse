#!/bin/bash
# 在 WSL 内用已缓存的 Gradle 9.3.1 构建本工程
set -euo pipefail
PROJ="${BSM_SRC:-$(cd "$(dirname "$0")/.." && pwd)}"   # 工程根目录（可用 BSM_SRC 覆盖）
GRADLE=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-9.3.1-bin/*/gradle-9.3.1/bin/gradle | head -1)
echo "gradle: $GRADLE"
cd "$PROJ"
exec "$GRADLE" "$@"
