#!/usr/bin/env bash
# SPDX-License-Identifier: MulanPSL-2.0
set -euo pipefail

PKG_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA17_HOME="${JAVA17_HOME:-$HOME/.local/share/jdks/jdk-17.0.19+10}"
MC_HOST="${MC_HOST:-127.0.0.1}"
MC_PORT="${MC_PORT:-25565}"
MC_USERNAME="${MC_USERNAME:-robonix_agent}"
MC_CLIENT_WIDTH="${MC_CLIENT_WIDTH:-1280}"
MC_CLIENT_HEIGHT="${MC_CLIENT_HEIGHT:-720}"
MC_OPTIONS_FILE="$PKG_ROOT/run/options.txt"

if [ ! -x "$JAVA17_HOME/bin/java" ]; then
  echo "[minecraft-mod/client] JDK 17 not found: $JAVA17_HOME" >&2
  exit 1
fi
if [ -z "${DISPLAY:-}" ]; then
  echo "[minecraft-mod/client] DISPLAY is not set" >&2
  exit 1
fi
if [[ ! "$MC_PORT" =~ ^[0-9]+$ ]] || (( MC_PORT < 1 || MC_PORT > 65535 )); then
  echo "[minecraft-mod/client] invalid MC_PORT: $MC_PORT" >&2
  exit 1
fi
if [[ ! "$MC_USERNAME" =~ ^[A-Za-z0-9_]{3,16}$ ]]; then
  echo "[minecraft-mod/client] invalid offline username: $MC_USERNAME" >&2
  exit 1
fi
if [[ ! "$MC_CLIENT_WIDTH" =~ ^[0-9]+$ ]] || [[ ! "$MC_CLIENT_HEIGHT" =~ ^[0-9]+$ ]]; then
  echo "[minecraft-mod/client] client width and height must be integers" >&2
  exit 1
fi

echo "[minecraft-mod/client] waiting for Minecraft server at $MC_HOST:$MC_PORT"
for _ in $(seq 1 60); do
  if nc -z -w 1 "$MC_HOST" "$MC_PORT" 2>/dev/null; then
    break
  fi
  sleep 0.5
done
if ! nc -z -w 1 "$MC_HOST" "$MC_PORT" 2>/dev/null; then
  echo "[minecraft-mod/client] Minecraft server is not reachable at $MC_HOST:$MC_PORT" >&2
  exit 1
fi

export JAVA_HOME="$JAVA17_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
export XAUTHORITY="${XAUTHORITY:-$HOME/.Xauthority}"

# Keep an unattended client rendering when its virtual window has no focus.
# Also skip the first-run accessibility screen so Quick Play can connect.
mkdir -p "$PKG_ROOT/run"
if [ -f "$MC_OPTIONS_FILE" ]; then
  awk '
    BEGIN {
      values["onboardAccessibility"] = "false"
      values["pauseOnLostFocus"] = "false"
      values["fullscreen"] = "false"
    }
    {
      split($0, fields, ":")
      key = fields[1]
      if (key in values) {
        if (!seen[key]++) print key ":" values[key]
        next
      }
    }
    { print }
    END {
      for (key in values) if (!seen[key]) print key ":" values[key]
    }
  ' "$MC_OPTIONS_FILE" >"$MC_OPTIONS_FILE.tmp"
  mv "$MC_OPTIONS_FILE.tmp" "$MC_OPTIONS_FILE"
else
  printf '%s\n' 'onboardAccessibility:false' 'pauseOnLostFocus:false' 'fullscreen:false' >"$MC_OPTIONS_FILE"
fi

echo "[minecraft-mod/client] starting $MC_USERNAME on $MC_HOST:$MC_PORT via $DISPLAY"
exec "$PKG_ROOT/gradlew" -p "$PKG_ROOT" runClient --args="--username $MC_USERNAME --quickPlayMultiplayer $MC_HOST:$MC_PORT --width $MC_CLIENT_WIDTH --height $MC_CLIENT_HEIGHT"
