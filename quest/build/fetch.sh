#!/usr/bin/env bash
# Reproducible fetch of Moonlight XR v0.3 + ALVR v20.14.1 client native lib.
#
# Inputs (pinned in quest/pins/pins.txt):
#   Moonlight XR v0.3 source            commit ae173a4966bb68af749d45ed87c8b9737a0ce570
#     moonlight-common-c (submodule)    commit 8af4562af672dd6b9ed28553ead172984fd9a683
#     enet (nested submodule)          commit d3a323fc8b9559786ee059205db74e8027c756f7
#   ALVR v20.14.1 client APK            SHA-256 be68feeb02665e3d69f1cdbcabf38ea4d15c42868a7ec6b5e698dbefee4e4e36
#     libalvr_client_openxr.so inside   SHA-256 extracted and re-hashed for integrity
#
# Outputs:
#   build/quest/upstream/                                                 Moonlight XR v0.3 at pinned SHA, all submodules initialized
#   build/quest/upstream/app/src/main/jniLibs/arm64-v8a/libalvr_client_openxr.so
#
# Idempotent: re-running after a successful fetch only re-verifies SHAs.
# Refuses to proceed if any SHA mismatch is detected.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_ROOT="${REPO_ROOT}/build/quest"
UPSTREAM="${BUILD_ROOT}/upstream"
ALVR_LIB_DIR="${UPSTREAM}/app/src/main/jniLibs/arm64-v8a"
ALVR_OUT_LIB="${ALVR_LIB_DIR}/libalvr_client_openxr.so"
APK_PATH="${BUILD_ROOT}/alvr_client_android_v20.14.1.apk"

PINS_FILE="${REPO_ROOT}/quest/pins/pins.txt"

MOONLIGHT_PIN="ae173a4966bb68af749d45ed87c8b9737a0ce570"
MOONLIGHT_COMMON_C_PIN="8af4562af672dd6b9ed28553ead172984fd9a683"
MOONLIGHT_ENET_PIN="d3a323fc8b9559786ee059205db74e8027c756f7"
MOONLIGHT_URL="https://github.com/Gilleece/moonlight-android-xr.git"

APK_URL="https://github.com/alvr-org/ALVR/releases/download/v20.14.1/alvr_client_android.apk"
APK_EXPECTED_SHA256="be68feeb02665e3d69f1cdbcabf38ea4d15c42868a7ec6b5e698dbefee4e4e36"

log() { printf '[fetch] %s\n' "$*" >&2; }
die() { printf '[fetch] ERROR: %s\n' "$*" >&2; exit 1; }

mkdir -p "${BUILD_ROOT}"

# 1. Moonlight XR v0.3 + recursive submodules.
if [[ ! -d "${UPSTREAM}/.git" ]]; then
  log "Cloning Moonlight XR pinned at ${MOONLIGHT_PIN}"
  git clone "${MOONLIGHT_URL}" "${UPSTREAM}"
  (
    cd "${UPSTREAM}"
    git checkout --detach "${MOONLIGHT_PIN}"
    git submodule update --init --recursive
  )
else
  log "Upstream tree already present, verifying SHAs only"
  ACTUAL_TOP="$(cd "${UPSTREAM}" && git rev-parse HEAD)"
  if [[ "${ACTUAL_TOP}" != "${MOONLIGHT_PIN}" ]]; then
    die "upstream HEAD ${ACTUAL_TOP} != pinned ${MOONLIGHT_PIN}; remove ${UPSTREAM} to re-fetch"
  fi
  # Ensure recursive submodules are initialised even if upstream was partly populated.
  if [[ ! -f "${UPSTREAM}/app/src/main/jni/moonlight-core/moonlight-common-c/CMakeLists.txt" \
     || ! -f "${UPSTREAM}/app/src/main/jni/moonlight-core/moonlight-common-c/enet/include/enet/enet.h" ]]; then
    (cd "${UPSTREAM}" && git submodule update --init --recursive)
  fi
fi

# 1a. Top-level commit must match the pin exactly.
ACTUAL_TOP="$(cd "${UPSTREAM}" && git rev-parse HEAD)"
[[ "${ACTUAL_TOP}" == "${MOONLIGHT_PIN}" ]] \
  || die "moonlight-xr HEAD ${ACTUAL_TOP} != pinned ${MOONLIGHT_PIN}"

# 1b. Submodule commits must match their respective pins.
ACTUAL_COMMON_C="$(cd "${UPSTREAM}/app/src/main/jni/moonlight-core/moonlight-common-c" \
                   && git rev-parse HEAD)"
[[ "${ACTUAL_COMMON_C}" == "${MOONLIGHT_COMMON_C_PIN}" ]] \
  || die "moonlight-common-c HEAD ${ACTUAL_COMMON_C} != pinned ${MOONLIGHT_COMMON_C_PIN}"

ACTUAL_ENET="$(cd "${UPSTREAM}/app/src/main/jni/moonlight-core/moonlight-common-c/enet" \
               && git rev-parse HEAD)"
[[ "${ACTUAL_ENET}" == "${MOONLIGHT_ENET_PIN}" ]] \
  || die "enet HEAD ${ACTUAL_ENET} != pinned ${MOONLIGHT_ENET_PIN}"

log "Upstream commits verified:"
log "  moonlight-xr        ${MOONLIGHT_PIN}"
log "  moonlight-common-c  ${MOONLIGHT_COMMON_C_PIN}"
log "  enet                ${MOONLIGHT_ENET_PIN}"

# 2. ALVR v20.14.1 APK download (only if missing or wrong SHA).
NEED_APK=1
if [[ -f "${APK_PATH}" ]]; then
  ACTUAL_SHA="$(sha256sum "${APK_PATH}" | awk '{print $1}')"
  if [[ "${ACTUAL_SHA}" == "${APK_EXPECTED_SHA256}" ]]; then
    NEED_APK=0
  fi
fi
if [[ "${NEED_APK}" -eq 1 ]]; then
  log "Downloading ALVR client APK from ${APK_URL}"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 -o "${APK_PATH}" "${APK_URL}"
  else
    wget -O "${APK_PATH}" "${APK_URL}"
  fi
fi

# 3. ALVR APK SHA-256 verification.
ACTUAL_SHA="$(sha256sum "${APK_PATH}" | awk '{print $1}')"
[[ "${ACTUAL_SHA}" == "${APK_EXPECTED_SHA256}" ]] \
  || die "ALVR APK SHA mismatch: expected ${APK_EXPECTED_SHA256}, got ${ACTUAL_SHA}"
log "ALVR APK SHA-256 verified: ${ACTUAL_SHA}"

# 4. Extract libalvr_client_openxr.so from the APK and verify integrity end-to-end.
mkdir -p "${ALVR_LIB_DIR}"
log "Extracting libalvr_client_openxr.so from APK"
unzip -j -o "${APK_PATH}" 'lib/arm64-v8a/libalvr_client_openxr.so' -d "${ALVR_LIB_DIR}"

APK_LIB_HASH="$(unzip -p "${APK_PATH}" 'lib/arm64-v8a/libalvr_client_openxr.so' \
                | sha256sum | awk '{print $1}')"
EXTRACTED_HASH="$(sha256sum "${ALVR_OUT_LIB}" | awk '{print $1}')"
[[ "${APK_LIB_HASH}" == "${EXTRACTED_HASH}" ]] \
  || die "extracted lib hash mismatch: APK=${APK_LIB_HASH} extracted=${EXTRACTED_HASH}"
log "Extracted lib SHA-256 verified: ${EXTRACTED_HASH}"

# 5. Refresh submodule states so downstream tooling sees clean entries.
(cd "${UPSTREAM}" && git submodule status --recursive > "${BUILD_ROOT}/submodule-status.txt")

log "Fetch complete."
log "  upstream tree : ${UPSTREAM}"
log "  APK           : ${APK_PATH}"
log "  native lib    : ${ALVR_OUT_LIB}"
log "  pin file      : ${PINS_FILE}"