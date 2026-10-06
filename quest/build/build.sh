#!/usr/bin/env bash
# Build the Vibertemis XR Preview APK from a freshly-fetched + overlaid
# upstream tree.
#
# This script:
#   1. Stages the pinned-NDK libc++_shared.so into jniLibs/ BEFORE Gradle
#      runs (the ALVR / Pyro / Pyrowave shared libraries all link against
#      libc++_shared.so, which Android's loader will not resolve from the
#      system; see quest/docs/native-runtime-provenance.md).
#   2. Writes an NDK runtime provenance sidecar next to the APK build
#      outputs so the release pack ships an exact-match runtime binary
#      and its pinned-NDK hash.
#   3. Runs the unit-test suite (testNonRootDebugUnitTest).
#   4. Assembles the nonRoot debug APK (assembleNonRootDebug).
#   5. Runs quest/build/verify-apk-native.py as a mandatory gate that
#      rejects any APK whose lib/<abi>/*.so set does not close the
#      DT_NEEDED closure and does not include libc++_shared.so +
#      libopenxr_loader.so + libalvr_client_openxr.so.
#
# Inputs:
#   build/quest/upstream/      prepared by quest/build/fetch.sh and
#                              quest/build/apply-overlays.sh
#   quest/pins/pins.txt        pinned toolchain versions
#
# Outputs:
#   build/quest/upstream/app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
#   build/quest/upstream/app/build/test-results/testNonRootDebugUnitTest/  JUnit XML
#   build/quest/upstream/app/build/ndk-runtime-provenance.txt            NDK + libc++_shared.so hash + provenance
#   build/quest/upstream/app/build/ndk-runtime-NOTICE.txt                pinned-NDK license notices for that runtime
#
# Environment overrides (all optional):
#   ANDROID_HOME / ANDROID_SDK_ROOT  path to Android SDK
#                                     default: $HOME/Android/Sdk (Linux/macOS convention)
#   JAVA_HOME                        JDK 17+ (AGP 9.4.0 needs JDK 17+)
#                                     default: PATH-resolved java
#   GRADLE_USER_HOME                 Gradle dependency cache
#                                     default: $HOME/.gradle
#
# Notes:
#   - AGP 9.4.0 requires JDK 17+; preview 14 was built with JDK 18.
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
# macOS: /usr/bin/java is a stub, not a JDK home, and Gradle hangs with
# JAVA_HOME=/usr, so ask the system for the real one.
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)"
fi
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

# 4. Locate the pinned NDK inside the SDK (it only stages libc++_shared.so
#    for the ALVR libraries; the app's own native code builds with the
#    ndkVersion upstream's build.gradle names) and write a fresh
#    local.properties so the SDK path is captured for Gradle.
NDK_DIR="${ANDROID_SDK}/ndk/${NDK_VERSION}"
[[ -d "${NDK_DIR}" ]] || die "NDK ${NDK_VERSION} not installed under ${ANDROID_SDK}/ndk; sdkmanager 'ndk;${NDK_VERSION}' first"

cat > "${UPSTREAM}/local.properties" <<EOF
sdk.dir=${ANDROID_SDK}
EOF

# 5. Export the SDK + NDK locations and the Gradle cache root.
export ANDROID_HOME="${ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK}"
if [[ -z "${GRADLE_USER_HOME:-}" ]]; then
  export GRADLE_USER_HOME="${HOME}/.gradle"
fi

log "JAVA_HOME          = ${JAVA_HOME}"
log "ANDROID_HOME       = ${ANDROID_HOME}"
log "Pinned NDK         = ${NDK_DIR}"
log "GRADLE_USER_HOME   = ${GRADLE_USER_HOME}"

cd "${UPSTREAM}"

python3 "${REPO_ROOT}/quest/native/check-android.py"

# 5a. Stage the NDK libc++_shared.so into the upstream jniLibs/ tree
#     BEFORE Gradle runs. The ALVR v20.14.1 native client, the Pyro
#     client and the Pyrowave shared library are all built with
#     ``-DANDROID_STL=c++_shared`` (see quest/native/build-android.sh)
#     and their DT_NEEDED chains reference ``libc++_shared.so``.
#     Android's loader will not resolve it from the system; the
#     runtime MUST be packaged under ``lib/arm64-v8a/`` in the APK.
#     This stage is the bounded packaging fix for the released
#     preview9 APK that shipped without this runtime (see
#     quest/docs/native-runtime-provenance.md).
JNILIBS_DIR="${UPSTREAM}/app/src/main/jniLibs/arm64-v8a"
# The NDK ships its host toolchain as linux-x86_64 or darwin-x86_64 (universal on Apple silicon)
case "$(uname -s)" in
  Darwin) NDK_HOST_TAG="darwin-x86_64" ;;
  *) NDK_HOST_TAG="linux-x86_64" ;;
esac
NDK_LLVM_BIN="${NDK_DIR}/toolchains/llvm/prebuilt/${NDK_HOST_TAG}/bin"
NDK_RUNTIME_SRC="${NDK_DIR}/toolchains/llvm/prebuilt/${NDK_HOST_TAG}/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
NDK_RUNTIME_DST="${JNILIBS_DIR}/libc++_shared.so"
mkdir -p "${JNILIBS_DIR}"
log "Staging libc++_shared.so from pinned NDK ${NDK_VERSION} (aarch64-linux-android)"
if [[ ! -f "${NDK_RUNTIME_SRC}" ]]; then
  die "pinned NDK ${NDK_VERSION} is missing libc++_shared.so at ${NDK_RUNTIME_SRC}; sdkmanager 'ndk;${NDK_VERSION}' first"
fi
# Always (re)stage from the pinned NDK; the prior build's runtime is
# replaced so a stale runtime cannot survive a rebuild. Other native libraries are stripped
# (see quest/native/build-android.sh) and we want the runtime to be
# stripped too so its size matches the rest of the package.
cp -f "${NDK_RUNTIME_SRC}" "${NDK_RUNTIME_DST}"
"${NDK_LLVM_BIN}/llvm-strip" --strip-unneeded "${NDK_RUNTIME_DST}"

# Record runtime provenance next to the built APK so the release
# pack can ship an exact-match binary + its pinned-NDK hash.
PROVENANCE_DIR="${UPSTREAM}/app/build"
mkdir -p "${PROVENANCE_DIR}"
PROVENANCE_FILE="${PROVENANCE_DIR}/ndk-runtime-provenance.txt"
RUNTIME_SHA="$(sha256sum "${NDK_RUNTIME_DST}" | awk '{print $1}')"
SOURCE_SHA="$(sha256sum "${NDK_RUNTIME_SRC}" | awk '{print $1}')"
{
  printf 'ndk_version         %s\n' "${NDK_VERSION}"
  printf 'runtime_source      %s\n' "${NDK_RUNTIME_SRC}"
  printf 'runtime_soname      libc++_shared.so\n'
  printf 'runtime_abi         aarch64-linux-android\n'
  printf 'runtime_sha256      %s\n' "${RUNTIME_SHA}"
  printf 'ndk_source_sha256   %s\n' "${SOURCE_SHA}"
  printf 'strip_status        stripped (llvm-strip --strip-unneeded)\n'
} > "${PROVENANCE_FILE}"
log "libc++_shared.so staged (sha256=${RUNTIME_SHA}); provenance written to ${PROVENANCE_FILE}"

# 5b. Concatenate the pinned NDK's license notices next to the
#      provenance sidecar so the release pack ships the runtime
#      attribution required for the packaged binary. This is
#      fail-closed: a missing notice aborts the build rather than
#      shipping a runtime whose attribution cannot be reproduced.
#      Both notices are copied verbatim under explicit section
#      labels; no line is rewritten, reordered or elided.
NDK_NOTICE="${NDK_DIR}/NOTICE"
NDK_TOOLCHAIN_NOTICE="${NDK_DIR}/NOTICE.toolchain"
if [[ ! -f "${NDK_NOTICE}" ]]; then
  die "pinned NDK ${NDK_VERSION} is missing ${NDK_NOTICE}; sdkmanager 'ndk;${NDK_VERSION}' first"
fi
if [[ ! -f "${NDK_TOOLCHAIN_NOTICE}" ]]; then
  die "pinned NDK ${NDK_VERSION} is missing ${NDK_TOOLCHAIN_NOTICE}; sdkmanager 'ndk;${NDK_VERSION}' first"
fi
NOTICE_FILE="${PROVENANCE_DIR}/ndk-runtime-NOTICE.txt"
{
  printf 'NDK runtime license notices\n'
  printf 'ndk_version         %s\n' "${NDK_VERSION}"
  printf 'ndk_dir             %s\n' "${NDK_DIR}"
  printf 'runtime_soname      libc++_shared.so\n'
  printf 'runtime_abi         aarch64-linux-android\n'
  printf 'runtime_sha256      %s\n' "${RUNTIME_SHA}"
  printf '\n'
  printf -- '===== BEGIN %s =====\n' "${NDK_NOTICE}"
  cat "${NDK_NOTICE}"
  printf '\n===== END %s =====\n\n' "${NDK_NOTICE}"
  printf -- '===== BEGIN %s =====\n' "${NDK_TOOLCHAIN_NOTICE}"
  cat "${NDK_TOOLCHAIN_NOTICE}"
  printf '\n===== END %s =====\n' "${NDK_TOOLCHAIN_NOTICE}"
} > "${NOTICE_FILE}"
log "NDK notices concatenated into ${NOTICE_FILE}"

# 6. First Quest3 test package: arm64-v8a only, minSdk 26, target 34.
#    Debug build is signed with the AGP debug keystore so users can sideload.
./gradlew \
    :app:testNonRootDebugUnitTest \
    :app:assembleNonRootDebug \
    -Pandroid.useAndroidX=true \
    --no-daemon \
    --stacktrace \
    --console=plain

APK="${UPSTREAM}/app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk"
log "Done."
log "APK                : ${APK}"
if [[ -f "${APK}" ]]; then
  log "APK SHA-256        : $(sha256sum "${APK}" | awk '{print $1}')"
fi

# 7. Native runtime closure verification (mandatory gate). The
#    verifier rejects any APK whose lib/<abi>/*.so set fails the
#    DT_NEEDED closure that Android's loader will walk at process
#    start, and it explicitly requires libc++_shared.so +
#    libopenxr_loader.so + libalvr_client_openxr.so to be packaged.
#    This catches a missing C++ runtime, missing transitive
#    dependency, wrong-ABI archive, duplicate SONAME or malformed
#    ELF before the APK is shipped. Verifier is a stdlib-only ELF
#    parser.
if [[ ! -f "${APK}" ]]; then
  die "assemble succeeded but APK missing at ${APK}"
fi
export ANDROID_NDK_HOME="${NDK_DIR}"
VERIFIER="${REPO_ROOT}/quest/build/verify-apk-native.py"
log "Running native runtime verifier: ${VERIFIER} ${APK}"
if ! python3 "${VERIFIER}" "${APK}" --runtime-sha256 "${RUNTIME_SHA}"; then
  die "native runtime verifier rejected the APK; see errors above"
fi
log "Native runtime verifier passed for ${APK}"
