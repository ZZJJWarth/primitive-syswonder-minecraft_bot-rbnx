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
# A system Python upgrade can leave an executable venv with incompatible deps.
if [ -f "$VENV/pyvenv.cfg" ] && ! python3 - "$VENV/pyvenv.cfg" <<'PY_VERSION'
import pathlib, sys
configured = next(line.split("=", 1)[1].strip() for line in pathlib.Path(sys.argv[1]).read_text().splitlines() if line.startswith(("version =", "version_info =")))
sys.exit(0 if configured.split(".")[:2] == [str(sys.version_info.major), str(sys.version_info.minor)] else 1)
PY_VERSION
then
  echo "[build] Python version changed; preserving old venv and rebuilding"
  mv "$VENV" "$VENV.backup.$(date +%Y%m%d%H%M%S)"
fi
if [ ! -x "$VENV/bin/python3" ]; then
  echo "[minecraft-mod/build] create codegen venv"
  python3 -m venv --system-site-packages "$VENV"
fi
if ! "$VENV/bin/python3" -c 'import grpc_tools.protoc, yaml, numpy, PIL; from mcp.server.fastmcp import FastMCP' 2>/dev/null; then
  echo "[minecraft-mod/build] install Python runtime/codegen deps"
  "$VENV/bin/python3" -m pip install grpcio-tools "mcp>=1.28.1,<2" \
    "pyyaml>=6" "numpy>=1.26" "pillow>=10"
fi
export PATH="$VENV/bin:$PATH"

echo "[minecraft-mod/build] rbnx codegen ${FLAGS[*]}"
rbnx codegen -p "$PKG" "${FLAGS[@]}"

echo "[minecraft-mod/build] provider smoke tests"
PYTHONPATH="$(rbnx path robonix-api):$PKG:$PKG/rbnx-build/codegen/proto_gen:$PKG/rbnx-build/codegen/robonix_mcp_types:${PYTHONPATH:-}" \
  "$VENV/bin/python3" -m unittest discover -s "$PKG/tests" -v

echo "[minecraft-mod/build] Forge jar"
"$PKG/gradlew" -p "$PKG" build

echo "[minecraft-mod/build] done"
