#!/usr/bin/env bash
# SPDX-License-Identifier: MulanPSL-2.0
set -euo pipefail

PKG_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENV="$PKG_ROOT/rbnx-build/codegen-venv"
USE_NVIDIA="${RBNX_MINECRAFT_USE_NVIDIA:-0}"
CLIENT_PID=""
PROVIDER_PID=""
VIEWER_PID=""
CHAT_PID=""
if [ ! -x "$VENV/bin/python3" ] || ! "$VENV/bin/python3" -c 'import grpc' >/dev/null 2>&1; then
  bash "$PKG_ROOT/scripts/build.sh"
fi

ROS_SETUP="${RBNX_MINECRAFT_ROS_SETUP:-}"
if [ -z "$ROS_SETUP" ]; then
  for candidate in "/opt/ros/${ROS_DISTRO:-lyrical}/setup.bash" /opt/ros/lyrical/setup.bash /opt/ros/jazzy/setup.bash /opt/ros/humble/setup.bash; do
    if [ -f "$candidate" ]; then
      ROS_SETUP="$candidate"
      break
    fi
  done
fi
if [ -z "$ROS_SETUP" ] || [ ! -f "$ROS_SETUP" ]; then
  echo "[minecraft-mod] ROS 2 setup not found; set RBNX_MINECRAFT_ROS_SETUP" >&2
  exit 1
fi
set +u
source "$ROS_SETUP"
set -u

export PYTHONPATH="$(rbnx path robonix-api):$PKG_ROOT:${PYTHONPATH:-}"

if [ "$USE_NVIDIA" != "0" ] && [ "$USE_NVIDIA" != "1" ]; then
  echo "[minecraft-mod] RBNX_MINECRAFT_USE_NVIDIA must be 0 or 1" >&2
  exit 1
fi

# Stops the current client launcher. The NVIDIA wrapper forwards termination
# to Minecraft and releases an owned Xorg; the inherited-display launcher is
# isolated in its own process group.
stop_client_launcher() {
  if [ -n "${ACTIVE_LAUNCH_PID:-}" ] && kill -0 "$ACTIVE_LAUNCH_PID" 2>/dev/null; then
    if (( ACTIVE_LAUNCH_IS_GROUP )); then
      kill -TERM -- "-$ACTIVE_LAUNCH_PID" 2>/dev/null || true
    else
      kill -TERM "$ACTIVE_LAUNCH_PID" 2>/dev/null || true
    fi
    wait "$ACTIVE_LAUNCH_PID" 2>/dev/null || true
  fi
  ACTIVE_LAUNCH_PID=""
}

# A requested off-screen display must stay off-screen even if setup fails.
# Propagate the failure instead of opening a client on the developer's desktop.
run_client() {
  local status
  ACTIVE_LAUNCH_PID=""
  ACTIVE_LAUNCH_IS_GROUP=0
  trap 'stop_client_launcher; exit 143' TERM
  trap 'stop_client_launcher; exit 130' INT

  if (( USE_NVIDIA )); then
    "$PKG_ROOT/scripts/run-nvidia-client.sh" &
    ACTIVE_LAUNCH_PID=$!
    if wait "$ACTIVE_LAUNCH_PID"; then
      status=0
    else
      status=$?
    fi
    ACTIVE_LAUNCH_PID=""
    if [ "$status" -eq 78 ]; then
      echo "[minecraft-mod] off-screen NVIDIA setup failed; check the display launcher output" >&2
    fi
    return "$status"
  fi

  ACTIVE_LAUNCH_IS_GROUP=1
  setsid "$PKG_ROOT/scripts/run-client.sh" &
  ACTIVE_LAUNCH_PID=$!
  if wait "$ACTIVE_LAUNCH_PID"; then
    status=0
  else
    status=$?
  fi
  ACTIVE_LAUNCH_PID=""
  return "$status"
}

# Stops all owned children when any one exits or rbnx boot tears down the
# package. This prevents a registered provider from outliving its client.
cleanup() {
  trap - EXIT INT TERM
  if [ -n "$PROVIDER_PID" ] && kill -0 "$PROVIDER_PID" 2>/dev/null; then
    kill -TERM "$PROVIDER_PID" 2>/dev/null || true
    wait "$PROVIDER_PID" 2>/dev/null || true
  fi
  if [ -n "$CLIENT_PID" ] && kill -0 "$CLIENT_PID" 2>/dev/null; then
    kill -TERM "$CLIENT_PID" 2>/dev/null || true
    wait "$CLIENT_PID" 2>/dev/null || true
  fi
  if [ -n "$VIEWER_PID" ] && kill -0 "$VIEWER_PID" 2>/dev/null; then
    kill -TERM "$VIEWER_PID" 2>/dev/null || true
    wait "$VIEWER_PID" 2>/dev/null || true
  fi
  if [ -n "$CHAT_PID" ] && kill -0 "$CHAT_PID" 2>/dev/null; then
    kill -TERM "$CHAT_PID" 2>/dev/null || true
    wait "$CHAT_PID" 2>/dev/null || true
  fi
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cd "$PKG_ROOT"
if nc -z -w 1 "${RBNX_MINECRAFT_MOD_HOST:-127.0.0.1}" \
  "${RBNX_MINECRAFT_MOD_PORT:-25576}" 2>/dev/null; then
  echo "[minecraft-mod] reusing the client already listening on the Mod command port"
else
  run_client &
  CLIENT_PID=$!
fi

"$VENV/bin/python3" "$PKG_ROOT/scripts/viewer.py" &
VIEWER_PID=$!
if [ "${RBNX_MINECRAFT_CHAT_ENABLED:-1}" != "0" ]; then
  "$VENV/bin/python3" -m minecraft_chat_bridge.bridge &
  CHAT_PID=$!
fi
"$VENV/bin/python3" -m minecraft_mod_driver.driver &
PROVIDER_PID=$!

WAIT_PIDS=("$VIEWER_PID" "$PROVIDER_PID")
if [ -n "$CHAT_PID" ]; then
  WAIT_PIDS+=("$CHAT_PID")
fi
if [ -n "$CLIENT_PID" ]; then
  WAIT_PIDS+=("$CLIENT_PID")
fi
FINISHED_PID=""
if wait -n -p FINISHED_PID "${WAIT_PIDS[@]}"; then
  FINISHED_STATUS=0
else
  FINISHED_STATUS=$?
fi

if [ -n "$CLIENT_PID" ] && [ "$FINISHED_PID" = "$CLIENT_PID" ]; then
  CLIENT_PID=""
  echo "[minecraft-mod] client exited; stopping provider" >&2
  if [ "$FINISHED_STATUS" -eq 0 ]; then
    exit 1
  fi
elif [ "$FINISHED_PID" = "$VIEWER_PID" ]; then
  VIEWER_PID=""
  echo "[minecraft-mod] web viewer exited; stopping provider and client" >&2
  if [ "$FINISHED_STATUS" -eq 0 ]; then
    exit 1
  fi
elif [ -n "$CHAT_PID" ] && [ "$FINISHED_PID" = "$CHAT_PID" ]; then
  CHAT_PID=""
  echo "[minecraft-mod] chat bridge exited; stopping provider and client" >&2
  if [ "$FINISHED_STATUS" -eq 0 ]; then
    exit 1
  fi
else
  PROVIDER_PID=""
fi
exit "$FINISHED_STATUS"
