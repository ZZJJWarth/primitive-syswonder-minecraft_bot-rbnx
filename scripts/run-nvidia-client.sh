#!/usr/bin/env bash
# SPDX-License-Identifier: MulanPSL-2.0
set -euo pipefail
umask 077

PKG_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NVIDIA_DISPLAY="${MC_NVIDIA_DISPLAY:-:48}"
NVIDIA_SETUP_EXIT=78
SETUP_ACTIVE=1
DISPLAY_NUMBER="${NVIDIA_DISPLAY#:}"
XORG_LAUNCH_PID=""
CLIENT_PID=""
STARTED_XORG=0
CREATED_XAUTH=0

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

RUNTIME_DIR="$(mktemp -d "/tmp/robonix-minecraft-xorg-${DISPLAY_NUMBER}-XXXXXX")"
XORG_CONFIG="$RUNTIME_DIR/xorg.conf"
XORG_LOG="$RUNTIME_DIR/Xorg.log"
XORG_PID_FILE="$RUNTIME_DIR/Xorg.pid"
XORG_LEASE_FILE="$RUNTIME_DIR/Xorg.lease"
AUTH_DIR="${XDG_RUNTIME_DIR:-$HOME/.cache/robonix}"
XAUTH_FILE="$AUTH_DIR/minecraft-xauth-${DISPLAY_NUMBER}"
LOCK_FILE="$AUTH_DIR/minecraft-display-${DISPLAY_NUMBER}.lock"

# Stops only the Xorg process created by this invocation and removes its
# temporary configuration and authorization files. A pre-existing display is
# deliberately left running for other clients.
cleanup() {
  if [ -n "$CLIENT_PID" ] && kill -0 "$CLIENT_PID" 2>/dev/null; then
    kill -TERM -- "-$CLIENT_PID" 2>/dev/null || true
    wait "$CLIENT_PID" 2>/dev/null || true
  fi
  if (( STARTED_XORG )); then
    rm -f "$XORG_LEASE_FILE" || true
    if [ -n "$XORG_LAUNCH_PID" ]; then
      wait "$XORG_LAUNCH_PID" 2>/dev/null || true
    fi
  fi
  if (( CREATED_XAUTH )); then
    rm -f "$XAUTH_FILE" || true
  fi
  rm -f "$XORG_CONFIG" "$XORG_LOG" "$XORG_PID_FILE" "$XORG_LEASE_FILE" || true
  rmdir "$RUNTIME_DIR" 2>/dev/null || true
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
  if [ -n "${XAUTHORITY:-}" ]; then
    CLIENT_XAUTHORITY="$XAUTHORITY"
  elif [ -f "$XAUTH_FILE" ]; then
    CLIENT_XAUTHORITY="$XAUTH_FILE"
  else
    CLIENT_XAUTHORITY="$HOME/.Xauthority"
  fi
else
  for command in Xorg xauth mcookie sudo; do
    if ! command -v "$command" >/dev/null 2>&1; then
      echo "[minecraft-mod/nvidia] required command not found: $command" >&2
      exit "$NVIDIA_SETUP_EXIT"
    fi
  done

  pick="$(
    nvidia-smi --query-gpu=index,memory.free,pci.bus_id \
      --format=csv,noheader,nounits 2>/dev/null \
      | sort -t',' -k2 -nr \
      | head -1
  )"
  if [ -z "$pick" ]; then
    echo "[minecraft-mod/nvidia] nvidia-smi returned no GPUs" >&2
    exit "$NVIDIA_SETUP_EXIT"
  fi

  IFS=',' read -r gpu_index free_mib busid_full <<<"$pick"
  gpu_index="${gpu_index//[[:space:]]/}"
  free_mib="${free_mib//[[:space:]]/}"
  busid_full="${busid_full//[[:space:]]/}"
  bus_hex_full="${busid_full#*:}"
  bus_hex="${bus_hex_full%%:*}"
  device_and_function="${bus_hex_full#*:}"
  device_hex="${device_and_function%.*}"
  function="${device_and_function#*.}"
  xorg_busid="PCI:$((16#$bus_hex)):$((16#$device_hex)):$function"

  cat >"$XORG_CONFIG" <<EOF
Section "ServerLayout"
  Identifier "Layout0"
  Screen 0 "Screen0"
EndSection
Section "Device"
  Identifier "Device0"
  Driver "nvidia"
  BusID "$xorg_busid"
EndSection
Section "Screen"
  Identifier "Screen0"
  Device "Device0"
  Option "AllowEmptyInitialConfiguration" "true"
  Option "UseDisplayDevice" "none"
  SubSection "Display"
    Virtual 1920 1080
    Depth 24
  EndSubSection
EndSection
EOF

  rm -f "$XAUTH_FILE"
  touch "$XAUTH_FILE"
  chmod 600 "$XAUTH_FILE"
  xauth -f "$XAUTH_FILE" add "$NVIDIA_DISPLAY" . "$(mcookie)"
  CREATED_XAUTH=1
  touch "$XORG_PID_FILE"
  touch "$XORG_LEASE_FILE"

  echo "[minecraft-mod/nvidia] requesting sudo access to start Xorg"
  SUDO_ARGS=()
  if [ -t 0 ]; then
    if ! sudo -v; then
      echo "[minecraft-mod/nvidia] sudo authorization failed" >&2
      exit "$NVIDIA_SETUP_EXIT"
    fi
  else
    SUDO_ARGS=(-n)
    if ! sudo -n -v; then
      echo "[minecraft-mod/nvidia] non-interactive sudo is unavailable" >&2
      exit "$NVIDIA_SETUP_EXIT"
    fi
  fi
  echo "[minecraft-mod/nvidia] starting GPU $gpu_index (free=${free_mib} MiB, $busid_full) on $NVIDIA_DISPLAY"
  sudo "${SUDO_ARGS[@]}" sh -c '
    Xorg "$2" -config "$3" -auth "$4" -noreset -nolisten tcp -logfile "$5" &
    xorg_pid=$!
    echo "$xorg_pid" >"$1"
    cleanup_xorg() {
      kill "$xorg_pid" 2>/dev/null || true
      wait "$xorg_pid" 2>/dev/null || true
    }
    trap cleanup_xorg EXIT INT TERM
    while [ -e "$6" ] && kill -0 "$7" 2>/dev/null; do
      if ! kill -0 "$xorg_pid" 2>/dev/null; then
        wait "$xorg_pid"
        exit $?
      fi
      sleep 1
    done
  ' sh "$XORG_PID_FILE" "$NVIDIA_DISPLAY" "$XORG_CONFIG" "$XAUTH_FILE" "$XORG_LOG" \
    "$XORG_LEASE_FILE" "$$" &
  XORG_LAUNCH_PID=$!
  STARTED_XORG=1

  for _ in $(seq 1 30); do
    [ -S "/tmp/.X11-unix/X${DISPLAY_NUMBER}" ] && break
    if ! kill -0 "$XORG_LAUNCH_PID" 2>/dev/null; then
      break
    fi
    sleep 0.5
  done
  if ! [ -S "/tmp/.X11-unix/X${DISPLAY_NUMBER}" ]; then
    echo "[minecraft-mod/nvidia] Xorg $NVIDIA_DISPLAY failed:" >&2
    tail -40 "$XORG_LOG" >&2 || true
    exit "$NVIDIA_SETUP_EXIT"
  fi
  if ! grep -Eq "NVIDIA\(0\):.*GPU|NVIDIA GPU" "$XORG_LOG" 2>/dev/null; then
    echo "[minecraft-mod/nvidia] Xorg did not initialize an NVIDIA GPU:" >&2
    tail -40 "$XORG_LOG" >&2 || true
    exit "$NVIDIA_SETUP_EXIT"
  fi
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
  "$PKG_ROOT/scripts/run-client.sh" &
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
