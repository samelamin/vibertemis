#!/usr/bin/env bash

set -euo pipefail

APP_PATH=${1:-}
DMG_PATH=${2:-}
SOURCE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
MOUNT_DIR=
LAUNCH_LOG=
LAUNCH_PID=
BUILD_INFO_FILE=
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
  if [[ -n "$BUILD_INFO_FILE" ]]; then
    rm -f "$BUILD_INFO_FILE"
    BUILD_INFO_FILE=
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
  local bundle_display_name
  local bundle_identifier
  local bundle_executable
  local dependency
  local launch_status

  [[ -x "$executable" ]] || fail "missing executable: $executable"
  [[ -f "$info_plist" ]] || fail "missing bundle metadata: $info_plist"

  BUILD_INFO_FILE=$(mktemp "${TMPDIR:-/tmp}/artemis-build-info.XXXXXX")
  "$executable" --build-info >"$BUILD_INFO_FILE" || \
    fail "unable to read native build identity from $executable"
  python3 - "$BUILD_INFO_FILE" "$EXPECTED_BUNDLE_VERSION" <<'PY'
import json
import os
import sys

with open(sys.argv[1], encoding="utf-8") as build_info_file:
    data = json.load(build_info_file)

assert data["schema"] == 1
assert data["applicationId"] == os.environ.get(
    "VIBERTEMIS_APPLICATION_ID", "com.artemis_desktop.Artemis"
)
if "VIBERTEMIS_BUILD_COMMIT" in os.environ:
    assert data["commit"] == os.environ["VIBERTEMIS_BUILD_COMMIT"]
if "VIBERTEMIS_UPDATE_CHANNEL" in os.environ:
    assert data["channel"] == os.environ["VIBERTEMIS_UPDATE_CHANNEL"]
if "VIBERTEMIS_BUILD_SEQUENCE" in os.environ:
    assert data["sequence"] == os.environ["VIBERTEMIS_BUILD_SEQUENCE"]
if sys.argv[2]:
    assert data["version"] == sys.argv[2]
assert data["internallyConsistent"] is True
PY
  rm -f "$BUILD_INFO_FILE"
  BUILD_INFO_FILE=

  bundle_display_name=$(/usr/libexec/PlistBuddy -c 'Print:CFBundleDisplayName' "$info_plist") || \
    fail "unable to read CFBundleDisplayName from $info_plist"
  bundle_identifier=$(/usr/libexec/PlistBuddy -c 'Print:CFBundleIdentifier' "$info_plist") || \
    fail "unable to read CFBundleIdentifier from $info_plist"
  bundle_executable=$(/usr/libexec/PlistBuddy -c 'Print:CFBundleExecutable' "$info_plist") || \
    fail "unable to read CFBundleExecutable from $info_plist"
  [[ "$bundle_display_name" == "Vibertemis" ]] || \
    fail "CFBundleDisplayName is $bundle_display_name; expected Vibertemis"
  [[ "$bundle_identifier" == "com.artemis_desktop.Artemis" ]] || \
    fail "CFBundleIdentifier is $bundle_identifier; expected com.artemis_desktop.Artemis"
  [[ "$bundle_executable" == "Artemis" ]] || \
    fail "CFBundleExecutable is $bundle_executable; expected Artemis"

  if [[ -n "$EXPECTED_BUNDLE_VERSION" ]]; then
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
  verify_app "$MOUNT_DIR/Vibertemis.app"
  hdiutil detach "$MOUNT_DIR" >/dev/null 2>&1
  rmdir "$MOUNT_DIR" 2>/dev/null || true
  MOUNT_DIR=
fi

echo "macOS bundle verification passed"
