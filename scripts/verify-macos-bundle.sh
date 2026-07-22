#!/usr/bin/env bash

set -euo pipefail

APP_PATH=${1:-}
DMG_PATH=${2:-}
MOUNT_DIR=

fail()
{
  echo "macOS bundle verification failed: $1" >&2
  exit 1
}

cleanup()
{
  if [[ -n "$MOUNT_DIR" ]] && mount | grep -Fq " on $MOUNT_DIR "; then
    hdiutil detach "$MOUNT_DIR" >/dev/null || true
  fi
  if [[ -n "$MOUNT_DIR" ]] && [[ -d "$MOUNT_DIR" ]]; then
    rmdir "$MOUNT_DIR" 2>/dev/null || true
  fi
}

trap cleanup EXIT

verify_app()
{
  local app_path=$1
  local executable="$app_path/Contents/MacOS/Artemis"
  local dependency
  local launch_log
  local launch_pid
  local launch_status

  [[ -x "$executable" ]] || fail "missing executable: $executable"

  echo "Architectures for $executable:"
  lipo -archs "$executable"

  echo "Linked libraries for $executable:"
  otool -L "$executable"

  while IFS= read -r dependency; do
    [[ -n "$dependency" ]] || continue

    case "$dependency" in
      /opt/homebrew/*|/usr/local/*)
        fail "deployed executable links directly to Homebrew dependency: $dependency"
        ;;
      /System/Library/*|/usr/lib/*|@*)
        ;;
      /*)
        [[ -e "$dependency" ]] || fail "missing absolute dependency: $dependency"
        ;;
    esac
  done < <(otool -L "$executable" | tail -n +2 | sed -E 's/^[[:space:]]*([^[:space:]]+).*/\1/')

  launch_log=$(mktemp "${TMPDIR:-/tmp}/artemis-launch.XXXXXX")
  QT_QPA_PLATFORM=offscreen SDL_VIDEODRIVER=dummy "$executable" >"$launch_log" 2>&1 &
  launch_pid=$!

  sleep 5
  if kill -0 "$launch_pid" 2>/dev/null; then
    kill "$launch_pid" 2>/dev/null || true
    wait "$launch_pid" 2>/dev/null || true
    rm -f "$launch_log"
    return 0
  fi

  set +e
  wait "$launch_pid"
  launch_status=$?
  set -e

  if [[ "$launch_status" -ne 0 ]]; then
    cat "$launch_log" >&2
    rm -f "$launch_log"
    fail "deployed executable exited immediately with status $launch_status"
  fi

  rm -f "$launch_log"
}

[[ -n "$APP_PATH" ]] || fail "usage: $0 APP_PATH [DMG_PATH]"
verify_app "$APP_PATH"

if [[ -n "$DMG_PATH" ]]; then
  [[ -f "$DMG_PATH" ]] || fail "missing DMG: $DMG_PATH"
  MOUNT_DIR=$(mktemp -d "${TMPDIR:-/tmp}/artemis-dmg.XXXXXX")
  hdiutil attach "$DMG_PATH" -nobrowse -readonly -mountpoint "$MOUNT_DIR" >/dev/null
  verify_app "$MOUNT_DIR/Artemis.app"
  hdiutil detach "$MOUNT_DIR" >/dev/null
  rmdir "$MOUNT_DIR"
  MOUNT_DIR=
fi

echo "macOS bundle verification passed"
