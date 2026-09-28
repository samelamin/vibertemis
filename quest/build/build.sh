#!/usr/bin/env bash
# Build the Vibertemis XR Preview APK from a freshly-fetched + overlaid
# upstream tree.
#
# This script runs the unit-test suite (testNonRootDebugUnitTest) and then
# assembles the nonRoot debug APK (assembleNonRootDebug).
#
# Inputs:
#   build/quest/upstream/      prepared by quest/build/fetch.sh and
#                              quest/build/apply-overlays.sh
#   quest/pins/pins.txt        pinned toolchain versions
#
# Outputs:
#   build/quest/upstream/app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
#   build/quest/upstream/app/build/test-results/testNonRootDebugUnitTest/  JUnit XML
#
# Environment overrides (all optional):
#   ANDROID_HOME / ANDROID_SDK_ROOT  path to Android SDK
#                                     default: $HOME/Android/Sdk (Linux/macOS convention)
#   JAVA_HOME                        JDK 17+ (AGP 8.5.1 supports JDK 17..21)
#                                     default: PATH-resolved java
#   GRADLE_USER_HOME                 Gradle dependency cache
#                                     default: $HOME/.gradle
#
# Notes:
#   - AGP 8.5.1 requires JDK 17+; this build was tested with JDK 21.
#   - NDK 27.0.12077973 (Python 3.12 toolchain) is pinned in quest/pins/pins.txt.
#   - Debug APKs are signed with the AGP debug keystore so users can sideload.
#   - No release keystore is shipped; see quest/README.md for instructions to
#     provide one via keystore.properties if the user wants a release build.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
UPSTREAM="${REPO_ROOT}/build/quest/upstream"
NDK_VERSION="27.0.12077973"

log() { printf '[build] %s\n' "$*" >&2; }
die() { printf '[build] ERROR: %s\n' "$*" >&2; exit 1; }

# 1. Sanity-check the upstream tree.
[[ -d "${UPSTREAM}" ]] || die "${UPSTREAM} missing; run quest/build/fetch.sh then quest/build/apply-overlays.sh"
[[ -x "${UPSTREAM}/gradlew" ]] || die "${UPSTREAM}/gradlew not executable"

# 2. Locate the Android SDK.
if [[ -n "${ANDROID_HOME:-}" ]]; then
  ANDROID_SDK="${ANDROID_HOME}"
elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
  ANDROID_SDK="${ANDROID_SDK_ROOT}"
elif [[ -d "${HOME}/Android/Sdk" ]]; then
  ANDROID_SDK="${HOME}/Android/Sdk"
else
  die "ANDROID_HOME / ANDROID_SDK_ROOT not set and no \$HOME/Android/Sdk found"
fi

# 3. Locate a JDK 17+ runtime.
if [[ -z "${JAVA_HOME:-}" ]]; then
  if command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(command -v java)"
    # Resolve symlinks so we get the real JDK directory, not /usr/bin/java.
    while [[ -L "${JAVA_BIN}" ]]; do JAVA_BIN="$(readlink "${JAVA_BIN}")"; done
    CANDIDATE_JAVA_HOME="$(dirname "$(dirname "${JAVA_BIN}")")"
    if [[ -x "${CANDIDATE_JAVA_HOME}/bin/javac" ]]; then
      JAVA_HOME="${CANDIDATE_JAVA_HOME}"
    fi
  fi
  if [[ -z "${JAVA_HOME:-}" ]]; then
    die "JAVA_HOME not set and no JDK 17+ found on PATH"
  fi
fi
export JAVA_HOME

# 4. Locate the NDK inside the SDK and write a fresh local.properties so the
#    SDK path is captured for Gradle.
NDK_DIR="${ANDROID_SDK}/ndk/${NDK_VERSION}"
[[ -d "${NDK_DIR}" ]] || die "NDK ${NDK_VERSION} not installed under ${ANDROID_SDK}/ndk; sdkmanager 'ndk;${NDK_VERSION}' first"

cat > "${UPSTREAM}/local.properties" <<EOF
sdk.dir=${ANDROID_SDK}
ndk.dir=${NDK_DIR}
EOF

# 5. Export the SDK + NDK locations and the Gradle cache root.
export ANDROID_HOME="${ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK}"
export ANDROID_NDK_HOME="${NDK_DIR}"
if [[ -z "${GRADLE_USER_HOME:-}" ]]; then
  export GRADLE_USER_HOME="${HOME}/.gradle"
fi

log "JAVA_HOME          = ${JAVA_HOME}"
log "ANDROID_HOME       = ${ANDROID_HOME}"
log "ANDROID_NDK_HOME   = ${ANDROID_NDK_HOME}"
log "GRADLE_USER_HOME   = ${GRADLE_USER_HOME}"

cd "${UPSTREAM}"

# 6. First Quest3 test package: arm64-v8a only, minSdk 26, target 34.
#    Debug build is signed with the AGP debug keystore so users can sideload.
./gradlew \
    :app:testNonRootDebugUnitTest \
    :app:assembleNonRootDebug \
    -Pandroid.useAndroidX=true \
    -Dorg.gradle.jvmargs="-Xmx3072m" \
    --no-daemon \
    --stacktrace \
    --console=plain

APK="${UPSTREAM}/app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk"
log "Done."
log "APK                : ${APK}"
if [[ -f "${APK}" ]]; then
  log "APK SHA-256        : $(sha256sum "${APK}" | awk '{print $1}')"
fi