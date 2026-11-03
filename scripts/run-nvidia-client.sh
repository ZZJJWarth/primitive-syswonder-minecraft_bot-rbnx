#!/usr/bin/env bash
# SPDX-License-Identifier: MulanPSL-2.0
set -euo pipefail
umask 077

PKG_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NVIDIA_DISPLAY="${MC_NVIDIA_DISPLAY:-:48}"
NVIDIA_DISPLAY=":${NVIDIA_DISPLAY#:}"
NVIDIA_SETUP_EXIT=78
SETUP_ACTIVE=1
DISPLAY_NUMBER="${NVIDIA_DISPLAY#:}"
XORG_LAUNCH_PID=""
CLIENT_PID=""
STARTED_XORG=0

# Maps any unexpected command failure before Minecraft launches to the setup
# status understood by start.sh. Runtime/client failures retain their original
# status after SETUP_ACTIVE is cleared.
handle_error() {
  local status=$?
  trap - ERR
  if (( SETUP_ACTIVE )); then
    echo "[minecraft-mod/nvidia] rendering setup command failed (status=$status)" >&2
    exit "$NVIDIA_SETUP_EXIT"
  fi
  exit "$status"
}

trap handle_error ERR

if [[ ! "$NVIDIA_DISPLAY" =~ ^:[0-9]+$ ]]; then
  echo "[minecraft-mod/nvidia] invalid MC_NVIDIA_DISPLAY: $NVIDIA_DISPLAY" >&2
  exit "$NVIDIA_SETUP_EXIT"
fi

AUTH_DIR="${XDG_RUNTIME_DIR:-$HOME/.cache/robonix}"
XAUTH_FILE="$AUTH_DIR/minecraft-xauth-${DISPLAY_NUMBER}"
LOCK_FILE="$AUTH_DIR/minecraft-client-${DISPLAY_NUMBER}.lock"

# Stops only the Xorg process created by this invocation and removes its
# temporary configuration and authorization files. A pre-existing display is
# deliberately left running for other clients.
cleanup() {
  if [ -n "$CLIENT_PID" ] && kill -0 "$CLIENT_PID" 2>/dev/null; then
    kill -TERM -- "-$CLIENT_PID" 2>/dev/null || true
    wait "$CLIENT_PID" 2>/dev/null || true
  fi
  if (( STARTED_XORG )) && [ -n "$XORG_LAUNCH_PID" ]; then
    kill -TERM "$XORG_LAUNCH_PID" 2>/dev/null || true
    wait "$XORG_LAUNCH_PID" 2>/dev/null || true
  fi
}

# Forwards termination to the isolated Minecraft process group, waits for its
# children, and then exits with the conventional signal status. The EXIT trap
# subsequently stops an Xorg process owned by this invocation.
forward_signal() {
  local signal="$1"
  local status="$2"
  trap - INT TERM
  if [ -n "$CLIENT_PID" ] && kill -0 "$CLIENT_PID" 2>/dev/null; then
    kill -"$signal" -- "-$CLIENT_PID" 2>/dev/null || true
    wait "$CLIENT_PID" 2>/dev/null || true
    CLIENT_PID=""
  fi
  exit "$status"
}

trap cleanup EXIT
trap 'forward_signal INT 130' INT
trap 'forward_signal TERM 143' TERM

mkdir -p "$AUTH_DIR"
for command in nvidia-smi xdpyinfo setsid flock; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "[minecraft-mod/nvidia] required command not found: $command" >&2
    exit "$NVIDIA_SETUP_EXIT"
  fi
done

exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  echo "[minecraft-mod/nvidia] another client wrapper is already using $NVIDIA_DISPLAY" >&2
  exit "$NVIDIA_SETUP_EXIT"
fi

if [ -S "/tmp/.X11-unix/X${DISPLAY_NUMBER}" ]; then
  existing_xorg_pid="$(
    ps -eo pid=,comm=,args= \
      | awk -v display="$NVIDIA_DISPLAY" '$2 == "Xorg" && $4 == display {print $1; exit}'
  )"
  if [ -z "$existing_xorg_pid" ] \
    || ! nvidia-smi pmon -c 1 2>/dev/null \
      | awk -v pid="$existing_xorg_pid" '$2 == pid {found = 1} END {exit !found}'; then
    echo "[minecraft-mod/nvidia] existing $NVIDIA_DISPLAY is not an NVIDIA Xorg display" >&2
    exit "$NVIDIA_SETUP_EXIT"
  fi
  echo "[minecraft-mod/nvidia] reusing NVIDIA Xorg $NVIDIA_DISPLAY (pid=$existing_xorg_pid)"
  if [ -f "$XAUTH_FILE" ]; then
    CLIENT_XAUTHORITY="$XAUTH_FILE"
  elif [ -n "${XAUTHORITY:-}" ]; then
    CLIENT_XAUTHORITY="$XAUTHORITY"
  else
    CLIENT_XAUTHORITY="$HOME/.Xauthority"
  fi
else
  "$PKG_ROOT/scripts/run-nvidia-display.sh" 9>&- &
  XORG_LAUNCH_PID=$!
  STARTED_XORG=1
  for _ in $(seq 1 60); do
    if DISPLAY="$NVIDIA_DISPLAY" XAUTHORITY="$XAUTH_FILE" xdpyinfo >/dev/null 2>&1; then
      break
    fi
    if ! kill -0 "$XORG_LAUNCH_PID" 2>/dev/null; then
      break
    fi
    sleep 0.25
  done
  CLIENT_XAUTHORITY="$XAUTH_FILE"
fi

if ! DISPLAY="$NVIDIA_DISPLAY" XAUTHORITY="$CLIENT_XAUTHORITY" \
  xdpyinfo >/dev/null 2>&1; then
  echo "[minecraft-mod/nvidia] cannot authenticate to Xorg $NVIDIA_DISPLAY with $CLIENT_XAUTHORITY" >&2
  exit "$NVIDIA_SETUP_EXIT"
fi

SETUP_ACTIVE=0
echo "[minecraft-mod/nvidia] launching Minecraft via $NVIDIA_DISPLAY"
setsid env DISPLAY="$NVIDIA_DISPLAY" XAUTHORITY="$CLIENT_XAUTHORITY" \
  "$PKG_ROOT/scripts/run-client.sh" 9>&- &
CLIENT_PID=$!
if wait "$CLIENT_PID"; then
  CLIENT_STATUS=0
else
  CLIENT_STATUS=$?
fi
CLIENT_PID=""
if [ "$CLIENT_STATUS" -eq "$NVIDIA_SETUP_EXIT" ]; then
  CLIENT_STATUS=1
fi
exit "$CLIENT_STATUS"
