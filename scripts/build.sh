#!/usr/bin/env bash
# SPDX-License-Identifier: MulanPSL-2.0
set -euo pipefail

PKG="${RBNX_PACKAGE_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
JAVA17_HOME="${JAVA17_HOME:-$HOME/.local/share/jdks/jdk-17.0.19+10}"
CLEAN="${RBNX_BUILD_CLEAN:-}"
FLAGS=(--mcp --ros2)
[[ "$CLEAN" == "1" ]] && FLAGS+=(--clean)

if [ ! -x "$JAVA17_HOME/bin/java" ]; then
  echo "[minecraft-mod/build] JDK 17 not found: $JAVA17_HOME" >&2
  exit 1
fi
export JAVA_HOME="$JAVA17_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

VENV="$PKG/rbnx-build/codegen-venv"
if [ ! -x "$VENV/bin/python3" ]; then
  echo "[minecraft-mod/build] create codegen venv"
  python3 -m venv "$VENV"
fi
if ! "$VENV/bin/python3" -c 'import grpc_tools.protoc, mcp, fastmcp, yaml, numpy, PIL' 2>/dev/null; then
  echo "[minecraft-mod/build] install Python runtime/codegen deps"
  "$VENV/bin/python3" -m pip install grpcio-tools "mcp>=1.0" "fastmcp>=3" \
    "pyyaml>=6" "numpy>=1.26" "pillow>=10"
fi
export PATH="$VENV/bin:$PATH"

echo "[minecraft-mod/build] rbnx codegen ${FLAGS[*]}"
rbnx codegen -p "$PKG" "${FLAGS[@]}"

echo "[minecraft-mod/build] provider smoke tests"
PYTHONPATH="$(rbnx path robonix-api):$PKG" \
  "$VENV/bin/python3" -m unittest discover -s "$PKG/tests" -v

echo "[minecraft-mod/build] Forge jar"
"$PKG/gradlew" -p "$PKG" build

echo "[minecraft-mod/build] done"
