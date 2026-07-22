#!/usr/bin/env bash

# This script requires create-dmg to be installed from https://github.com/sindresorhus/create-dmg
set -euo pipefail

BUILD_CONFIG=${1:-}
OVERRIDE_VERSION=${2:-}
SIGNING_PROVIDER_SHORTNAME=${SIGNING_PROVIDER_SHORTNAME:-}
SIGNING_IDENTITY=${SIGNING_IDENTITY:-}
NOTARY_KEYCHAIN_PROFILE=${NOTARY_KEYCHAIN_PROFILE:-}
ARTEMIS_MAC_ARCHS=${ARTEMIS_MAC_ARCHS:-$(uname -m)}
MACOSX_DEPLOYMENT_TARGET=${MACOSX_DEPLOYMENT_TARGET:-14.0}

fail()
{
  echo "$1" >&2
  exit 1
}

if [[ "$BUILD_CONFIG" != "Debug" ]] && [[ "$BUILD_CONFIG" != "Release" ]]; then
  fail "Invalid build configuration - expected 'Debug' or 'Release'"
fi

SOURCE_ROOT=$PWD
BUILD_ROOT="$SOURCE_ROOT/build"
BUILD_FOLDER="$BUILD_ROOT/build-$BUILD_CONFIG"
INSTALLER_FOLDER="$BUILD_ROOT/installer-$BUILD_CONFIG"
APP_PATH="$BUILD_FOLDER/app/Artemis.app"
APP_EXECUTABLE="$APP_PATH/Contents/MacOS/Artemis"

# Use override version if provided, otherwise read from version.txt.
if [[ -n "$OVERRIDE_VERSION" ]]; then
  VERSION=$OVERRIDE_VERSION
else
  VERSION=$(<"$SOURCE_ROOT/app/version.txt")
fi

if [[ -z "$SIGNING_PROVIDER_SHORTNAME" ]]; then
  SIGNING_PROVIDER_SHORTNAME=$SIGNING_IDENTITY
fi
if [[ -z "$SIGNING_IDENTITY" ]]; then
  SIGNING_IDENTITY=$SIGNING_PROVIDER_SHORTNAME
fi

[[ -z "$SIGNING_IDENTITY" ]] || git diff-index --quiet HEAD -- || fail "Signed release builds must not have unstaged changes!"

echo "Cleaning output directories"
rm -rf "$BUILD_FOLDER" "$INSTALLER_FOLDER"
mkdir -p "$BUILD_FOLDER" "$INSTALLER_FOLDER"

echo "Configuring the project for: $ARTEMIS_MAC_ARCHS"
pushd "$BUILD_FOLDER" >/dev/null
qmake "$SOURCE_ROOT/artemis.pro" \
  "QMAKE_APPLE_DEVICE_ARCHS=$ARTEMIS_MAC_ARCHS" \
  "QMAKE_MACOSX_DEPLOYMENT_TARGET=$MACOSX_DEPLOYMENT_TARGET" || fail "Qmake failed!"
popd >/dev/null

echo "Compiling Artemis in $BUILD_CONFIG configuration"
pushd "$BUILD_FOLDER" >/dev/null
make -j"$(sysctl -n hw.logicalcpu)" "$(echo "$BUILD_CONFIG" | tr '[:upper:]' '[:lower:]')" || fail "Make failed!"
popd >/dev/null

echo "Saving dSYM file"
pushd "$BUILD_FOLDER" >/dev/null
dsymutil "$APP_EXECUTABLE" -o "Artemis-$VERSION.dsym" || fail "dSYM creation failed!"
cp -R "Artemis-$VERSION.dsym" "$INSTALLER_FOLDER/" || fail "dSYM copy failed!"
popd >/dev/null

echo "Creating app bundle"
DEPLOY_ARGS=()
if [[ "$BUILD_CONFIG" == "Debug" ]]; then
  DEPLOY_ARGS+=("-use-debug-libs")
fi
if [[ ${#DEPLOY_ARGS[@]} -gt 0 ]]; then
  echo "Extra deployment arguments: ${DEPLOY_ARGS[*]}"
fi
macdeployqt "$APP_PATH" \
  "${DEPLOY_ARGS[@]}" \
  "-qmldir=$SOURCE_ROOT/app/gui" \
  -appstore-compliant || fail "macdeployqt failed!"

echo "Removing dSYM files from app bundle"
find "$APP_PATH" -type d -name '*.dSYM' -prune -exec rm -rf {} +

if [[ -n "$SIGNING_IDENTITY" ]]; then
  echo "Signing app bundle with entitlements"
  codesign --force --deep --options runtime --timestamp \
    --entitlements "$SOURCE_ROOT/scripts/entitlements.plist" \
    --sign "$SIGNING_IDENTITY" \
    "$APP_PATH" || fail "Signing failed!"
fi

echo "Creating DMG"
DMG_NAME="Artemis-$VERSION.dmg"
DMG_PATH="$INSTALLER_FOLDER/$DMG_NAME"

create_styled_dmg()
{
  local create_dmg_args=(
    --volname "Artemis"
    --volicon "$SOURCE_ROOT/app/artemis.icns"
    --background "$SOURCE_ROOT/scripts/dmg-background.png"
    --window-pos 200 120
    --window-size 660 400
    --icon-size 100
    --icon "Artemis.app" 180 170
    --hide-extension "Artemis.app"
    --app-drop-link 480 170
    --no-internet-enable
  )

  if [[ -n "$SIGNING_IDENTITY" ]]; then
    create_dmg_args+=("--identity=$SIGNING_IDENTITY")
  fi

  create-dmg "${create_dmg_args[@]}" "$DMG_PATH" "$APP_PATH"
}

create_fallback_dmg()
{
  hdiutil create \
    -volname "Artemis" \
    -srcfolder "$APP_PATH" \
    -ov \
    -format UDZO \
    "$DMG_PATH" || fail "DMG creation failed even with fallback method!"

  if [[ -n "$SIGNING_IDENTITY" ]]; then
    codesign --force --sign "$SIGNING_IDENTITY" "$DMG_PATH" || \
      echo "Warning: DMG signing failed but DMG was created"
  fi
}

if [[ -n "$SIGNING_IDENTITY" ]]; then
  echo "Creating signed DMG with custom styling..."
else
  echo "Creating unsigned DMG with custom styling..."
fi

create_styled_dmg || {
  echo "create-dmg failed! Trying fallback method..."
  create_fallback_dmg
}

if [[ -n "$NOTARY_KEYCHAIN_PROFILE" ]]; then
  echo "Uploading to App Notary service"
  xcrun notarytool submit \
    --keychain-profile "$NOTARY_KEYCHAIN_PROFILE" \
    --wait \
    "$DMG_PATH" || fail "Notary submission failed"

  echo "Stapling notary ticket to DMG"
  xcrun stapler staple -v "$DMG_PATH" || fail "Notary ticket stapling failed!"
fi

ACTUAL_ARCHS=$(lipo -archs "$APP_EXECUTABLE")
if [[ " $ACTUAL_ARCHS " == *" arm64 "* ]] && [[ " $ACTUAL_ARCHS " == *" x86_64 "* ]]; then
  ARCHITECTURE_LABEL="Universal (x86_64 + arm64)"
  ARCHITECTURE_NOTE="This is a universal binary that works on both Intel and Apple Silicon Macs"
else
  ARCHITECTURE_LABEL=$ACTUAL_ARCHS
  ARCHITECTURE_NOTE="This build contains these architecture slices: $ACTUAL_ARCHS"
fi

cat >"$INSTALLER_FOLDER/build_info_macos.txt" <<EOF
Artemis Desktop macOS $ARCHITECTURE_LABEL Development Build
Version: $VERSION
Architecture: $ARCHITECTURE_LABEL
Build Configuration: $BUILD_CONFIG
Built: $(date -u '+%Y-%m-%d %H:%M:%S UTC')

Installation Notes:
- $ARCHITECTURE_NOTE
- If macOS says the app is "damaged", run: xattr -cr Artemis.app
- Or go to System Settings > Privacy & Security and allow the app
- This is a development build and may trigger Gatekeeper warnings
EOF

echo "Build successful: $DMG_PATH ($ACTUAL_ARCHS)"
