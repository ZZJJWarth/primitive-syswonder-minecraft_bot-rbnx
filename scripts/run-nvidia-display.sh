#!/usr/bin/env bash
# SPDX-License-Identifier: MulanPSL-2.0
# A rootless, off-screen NVIDIA X server. Safe to run alongside a local desktop.
set -euo pipefail
umask 077

NVIDIA_DISPLAY="${MC_NVIDIA_DISPLAY:-:48}"
NVIDIA_DISPLAY=":${NVIDIA_DISPLAY#:}"
if [[ ! "$NVIDIA_DISPLAY" =~ ^:[0-9]+$ ]]; then
  echo "[minecraft-mod/display] invalid MC_NVIDIA_DISPLAY" >&2
  exit 78
fi
DISPLAY_NUMBER="${NVIDIA_DISPLAY#:}"
AUTH_DIR="${XDG_RUNTIME_DIR:-$HOME/.cache/robonix}"
XAUTH_FILE="$AUTH_DIR/minecraft-xauth-${DISPLAY_NUMBER}"
RUNTIME_DIR=""
XORG_PID=""
CREATED_XAUTH=0

cleanup() {
  if [ -n "$XORG_PID" ]; then
    kill -TERM "$XORG_PID" 2>/dev/null || true
    wait "$XORG_PID" 2>/dev/null || true
  fi
  if (( CREATED_XAUTH )); then
    rm -f "$XAUTH_FILE"
  fi
  if [ -n "$RUNTIME_DIR" ]; then
    rm -f "$RUNTIME_DIR/xorg.conf" "$RUNTIME_DIR/Xorg.log" "$RUNTIME_DIR/Xorg.log.old"
    rmdir "$RUNTIME_DIR" 2>/dev/null || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for command in nvidia-smi xauth mcookie flock xdpyinfo; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "[minecraft-mod/display] required command not found: $command" >&2
    exit 78
  fi
done
# Bypass the console-only Xorg.wrap: a non-seat0 server needs no VT or root.
XORG_BIN=""
for candidate in /usr/lib/xorg/Xorg /usr/libexec/Xorg; do
  if [ -x "$candidate" ]; then
    XORG_BIN="$candidate"
    break
  fi
done
if [ -z "$XORG_BIN" ]; then
  echo "[minecraft-mod/display] native Xorg binary not found" >&2
  exit 78
fi

mkdir -p "$AUTH_DIR"
exec 8>"$AUTH_DIR/minecraft-display-${DISPLAY_NUMBER}.lock"
if ! flock -n 8 || [ -S "/tmp/.X11-unix/X${DISPLAY_NUMBER}" ]; then
  echo "[minecraft-mod/display] $NVIDIA_DISPLAY is already in use" >&2
  exit 78
fi
RUNTIME_DIR="$(mktemp -d "/tmp/robonix-minecraft-xorg-${DISPLAY_NUMBER}-XXXXXX")"
XORG_CONFIG="$RUNTIME_DIR/xorg.conf"
XORG_LOG="$RUNTIME_DIR/Xorg.log"

pick="$(nvidia-smi --query-gpu=index,memory.free,pci.bus_id --format=csv,noheader,nounits | sort -t',' -k2 -nr | head -1)"
if [ -z "$pick" ]; then
  echo "[minecraft-mod/display] nvidia-smi returned no GPUs" >&2
  exit 78
fi
IFS=',' read -r gpu_index free_mib busid_full <<<"$pick"
gpu_index="${gpu_index//[[:space:]]/}"
busid_full="${busid_full//[[:space:]]/}"
bus_hex_full="${busid_full#*:}"
bus_hex="${bus_hex_full%%:*}"
device_and_function="${bus_hex_full#*:}"
device_hex="${device_and_function%.*}"
function="${device_and_function#*.}"
xorg_busid="PCI:$((16#$bus_hex)):$((16#$device_hex)):$function"

# AutoAddGPU is disabled, so add the NVIDIA module path explicitly instead of
# relying on an OutputClass matched by automatic GPU discovery.
printf 'Section "Files"\n' >"$XORG_CONFIG"
for module_dir in /usr/lib/*/nvidia/xorg /usr/lib/nvidia/xorg /usr/lib64/nvidia/xorg /usr/lib/xorg/modules /usr/lib64/xorg/modules; do
  if [ -d "$module_dir" ]; then
    printf '  ModulePath "%s"\n' "$module_dir" >>"$XORG_CONFIG"
  fi
done
cat >>"$XORG_CONFIG" <<EOF
EndSection
Section "ServerFlags"
  Option "AutoAddDevices" "false"
  Option "AutoEnableDevices" "false"
  Option "AutoAddGPU" "false"
  Option "AutoBindGPU" "false"
EndSection
Section "ServerLayout"
  Identifier "MinecraftLayout"
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
CREATED_XAUTH=1
xauth -f "$XAUTH_FILE" add "$NVIDIA_DISPLAY" . "$(mcookie)"

echo "[minecraft-mod/display] starting rootless GPU $gpu_index ($busid_full) on $NVIDIA_DISPLAY"
"$XORG_BIN" "$NVIDIA_DISPLAY" -seat "minecraft-${DISPLAY_NUMBER}" \
  -config "$XORG_CONFIG" -auth "$XAUTH_FILE" \
  -novtswitch -sharevts -isolateDevice "$xorg_busid" \
  -noreset -nolisten tcp -logfile "$XORG_LOG" &
XORG_PID=$!
for _ in $(seq 1 60); do
  if DISPLAY="$NVIDIA_DISPLAY" XAUTHORITY="$XAUTH_FILE" xdpyinfo >/dev/null 2>&1; then
    echo "[minecraft-mod/display] ready; DISPLAY=$NVIDIA_DISPLAY XAUTHORITY=$XAUTH_FILE"
    wait "$XORG_PID"
    exit $?
  fi
  if ! kill -0 "$XORG_PID" 2>/dev/null; then
    break
  fi
  sleep 0.25
done
echo "[minecraft-mod/display] Xorg failed to become ready:" >&2
tail -40 "$XORG_LOG" >&2 || true
exit 78
