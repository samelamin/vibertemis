#!/usr/bin/env bash

set -euo pipefail

APP_PATH=${1:-}
DMG_PATH=${2:-}
SOURCE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
MOUNT_DIR=
LAUNCH_LOG=
LAUNCH_PID=
EXPECTED_BUNDLE_VERSION=

if [[ ${ARTEMIS_EXPECTED_BUNDLE_VERSION+x} == x ]]; then
  EXPECTED_BUNDLE_VERSION=$ARTEMIS_EXPECTED_BUNDLE_VERSION
elif [[ -f "$SOURCE_ROOT/app/version.txt" ]]; then
  EXPECTED_BUNDLE_VERSION=$(tr -d '\r\n' <"$SOURCE_ROOT/app/version.txt")
fi

fail()
{
  echo "macOS bundle verification failed: $1" >&2
  exit 1
}

stop_process()
{
  local pid=$1
  local attempts=0

  kill -TERM "$pid" 2>/dev/null || true
  while kill -0 "$pid" 2>/dev/null && [[ "$attempts" -lt 10 ]]; do
    sleep 0.2
    attempts=$((attempts + 1))
  done

  if kill -0 "$pid" 2>/dev/null; then
    kill -KILL "$pid" 2>/dev/null || true
  fi
  wait "$pid" 2>/dev/null || true
}

cleanup()
{
  if [[ -n "$LAUNCH_PID" ]]; then
    stop_process "$LAUNCH_PID"
    LAUNCH_PID=
  fi
  if [[ -n "$LAUNCH_LOG" ]]; then
    rm -f "$LAUNCH_LOG"
    LAUNCH_LOG=
  fi
  if [[ -n "$MOUNT_DIR" ]]; then
    hdiutil detach "$MOUNT_DIR" >/dev/null 2>&1 || true
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
  local info_plist="$app_path/Contents/Info.plist"
  local bundle_short_version
  local bundle_version
  local dependency
  local launch_status

  [[ -x "$executable" ]] || fail "missing executable: $executable"

  if [[ -n "$EXPECTED_BUNDLE_VERSION" ]]; then
    [[ -f "$info_plist" ]] || fail "missing bundle metadata: $info_plist"
    bundle_short_version=$(/usr/libexec/PlistBuddy -c 'Print:CFBundleShortVersionString' "$info_plist") || \
      fail "unable to read CFBundleShortVersionString from $info_plist"
    bundle_version=$(/usr/libexec/PlistBuddy -c 'Print:CFBundleVersion' "$info_plist") || \
      fail "unable to read CFBundleVersion from $info_plist"
    [[ "$bundle_short_version" == "$EXPECTED_BUNDLE_VERSION" ]] || \
      fail "CFBundleShortVersionString is $bundle_short_version; expected $EXPECTED_BUNDLE_VERSION"
    [[ "$bundle_version" == "$EXPECTED_BUNDLE_VERSION" ]] || \
      fail "CFBundleVersion is $bundle_version; expected $EXPECTED_BUNDLE_VERSION"
  fi

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

  LAUNCH_LOG=$(mktemp "${TMPDIR:-/tmp}/artemis-launch.XXXXXX")
  QT_QPA_PLATFORM=cocoa SDL_VIDEODRIVER=cocoa "$executable" >"$LAUNCH_LOG" 2>&1 &
  LAUNCH_PID=$!

  sleep 5
  if kill -0 "$LAUNCH_PID" 2>/dev/null; then
    stop_process "$LAUNCH_PID"
    LAUNCH_PID=
    rm -f "$LAUNCH_LOG"
    LAUNCH_LOG=
    return 0
  fi

  set +e
  wait "$LAUNCH_PID"
  launch_status=$?
  set -e
  LAUNCH_PID=

  if [[ "$launch_status" -ne 0 ]]; then
    cat "$LAUNCH_LOG" >&2
    rm -f "$LAUNCH_LOG"
    LAUNCH_LOG=
    fail "deployed executable exited immediately with status $launch_status"
  fi

  rm -f "$LAUNCH_LOG"
  LAUNCH_LOG=
}

[[ -n "$APP_PATH" ]] || fail "usage: $0 APP_PATH [DMG_PATH]"
verify_app "$APP_PATH"

if [[ -n "$DMG_PATH" ]]; then
  [[ -f "$DMG_PATH" ]] || fail "missing DMG: $DMG_PATH"
  MOUNT_DIR=$(mktemp -d "${TMPDIR:-/tmp}/artemis-dmg.XXXXXX")
  hdiutil attach "$DMG_PATH" -nobrowse -readonly -mountpoint "$MOUNT_DIR" >/dev/null
  verify_app "$MOUNT_DIR/Artemis.app"
  hdiutil detach "$MOUNT_DIR" >/dev/null 2>&1
  rmdir "$MOUNT_DIR" 2>/dev/null || true
  MOUNT_DIR=
fi

echo "macOS bundle verification passed"
