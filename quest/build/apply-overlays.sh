#!/usr/bin/env bash
# Apply the overlay Java/resources/tests + git patches onto the generated
# upstream tree under REPOROOT/build/quest/upstream/.
#
# Idempotent: re-running after a successful apply is safe. Drop additional
# patches into quest/patches/ in numeric order and re-run.
#
# Safety properties:
#   1. ONLY the generated tree REPOROOT/build/quest/upstream/ may be mutated.
#      Refuses to operate on any other path, including the same physical
#      tree accessed via a symlink, a /tmp clone, or any reference tree.
#      This is enforced via canonical (physical) path + git toplevel
#      equality, NOT a /tmp blacklist (legitimate repos under /tmp may
#      exist; the rule is about which tree is the generated one).
#   2. Refuses if upstream HEAD does not match the pinned commit.
#   3. Restores tracked files modified by the consolidated patch to HEAD so
#      a re-run sees a clean tree.
#   4. Removes ONLY the overlay-managed directories under
#      app/src/main/java/com/vibertemis and app/src/test/java/com/vibertemis
#      before re-copying (no rsync --delete on the whole upstream tree).
#   5. Patch context blank lines contain a single leading space BY DESIGN;
#      do not trim them (silences `git diff --check` correctly).
#   6. Patch ordering is numeric (0001, 0002, ...). Each patch is checked
#      individually with `git apply --check` and then applied before the
#      next patch is checked.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
EXPECTED_UPSTREAM="${REPO_ROOT}/build/quest/upstream"
OVERLAY="${REPO_ROOT}/quest/overlay"
PATCHES_DIR="${REPO_ROOT}/quest/patches"

MOONLIGHT_PIN="686429ab8c91ad1c8bb128a7c7e3188802c3bcf4"

log() { printf '[overlay] %s\n' "$*" >&2; }
die() { printf '[overlay] ERROR: %s\n' "$*" >&2; exit 1; }

# 1. Reject any path that is not the canonical REPO_ROOT/build/quest/upstream.
#    We require the canonical physical path of the directory to EQUAL the
#    expected path, AND the git toplevel reported by the upstream tree to
#    EQUAL the same. This rejects symlink redirects, /tmp clones, and any
#    reference tree that happens to be at the pinned HEAD.
[[ -d "${EXPECTED_UPSTREAM}" ]] \
  || die "expected upstream ${EXPECTED_UPSTREAM} does not exist; run quest/build/fetch.sh first"
[[ ! -L "${EXPECTED_UPSTREAM}" ]] \
  || die "${EXPECTED_UPSTREAM} is a symlink; replace it with a real directory"

CANONICAL_EXPECTED="$(cd "${EXPECTED_UPSTREAM}" && pwd -P)"
[[ -d "${CANONICAL_EXPECTED}/.git" ]] \
  || die "${EXPECTED_UPSTREAM} is not a git working tree"

CANONICAL_TOPLEVEL="$(cd "${CANONICAL_EXPECTED}" && git rev-parse --show-toplevel)"
CANONICAL_TOPLEVEL="$(cd "${CANONICAL_TOPLEVEL}" && pwd -P)"

if [[ "${CANONICAL_TOPLEVEL}" != "${CANONICAL_EXPECTED}" ]]; then
  die "${EXPECTED_UPSTREAM} canonicalises to ${CANONICAL_EXPECTED} but git toplevel resolves to ${CANONICAL_TOPLEVEL}; refusing to operate on a redirected tree"
fi
# Exact-equality guard: the canonical physical path of the target must be
# EXACTLY the physical REPO_ROOT/build/quest/upstream. A prefix check alone
# would still permit a parent-redirect within the repo (e.g. a sibling
# tree at REPO_ROOT/build/quest/upstream-extra); this exact-match closes
# that hole before any mutation runs.
if [[ "${CANONICAL_EXPECTED}" != "${EXPECTED_UPSTREAM}" ]]; then
  die "${EXPECTED_UPSTREAM} canonicalises to ${CANONICAL_EXPECTED}; refusing to operate on a redirected tree"
fi

UPSTREAM="${CANONICAL_EXPECTED}"

# 2. Refuse if upstream HEAD does not match the pinned commit.
ACTUAL_HEAD="$(cd "${UPSTREAM}" && git rev-parse HEAD)"
[[ "${ACTUAL_HEAD}" == "${MOONLIGHT_PIN}" ]] \
  || die "upstream HEAD ${ACTUAL_HEAD} != pinned ${MOONLIGHT_PIN}; re-run fetch.sh"

# 3. Restore tracked files modified by the consolidated patch to HEAD so the
#    next `git apply` sees a clean tree. Without this, a re-run on a partially
#    applied state can produce silent failures.
(cd "${UPSTREAM}" && git checkout -- \
    app/build.gradle \
    app/proguard-rules.pro \
    app/src/main/AndroidManifest.xml \
    app/src/main/java/com/limelight/Game.java \
    app/src/main/java/com/limelight/preferences/PreferenceConfiguration.java \
    app/src/main/java/com/limelight/preferences/StreamSettings.java \
    app/src/main/res/layout/activity_stream_settings.xml \
    app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml \
    app/src/main/res/xml/preferences.xml \
    gradle.properties)

# 4. Remove ONLY the overlay-managed directories before re-copying.
for managed in \
    app/src/main/java/com/vibertemis \
    app/src/test/java/com/vibertemis ; do
  if [[ -d "${UPSTREAM}/${managed}" ]]; then
    rm -rf "${UPSTREAM:?}/${managed}"
    log "removed managed ${managed}"
  fi
done

# 5. Refuse if there are unexpected modifications outside the overlay.
UNEXPECTED="$(cd "${UPSTREAM}" && git diff --name-only HEAD \
  | grep -v -E '^(app/src/main/java/com/vibertemis/|app/src/main/res/mipmap-anydpi-v26/ic_launcher\.xml$|app/src/main/res/drawable/ic_vibertemis_preview\.xml$|app/src/main/res/layout/vibertemis_hub\.xml$|app/src/main/res/values/vibertemis_colors\.xml$|app/src/main/res/values/vibertemis_strings\.xml$|app/src/test/java/com/vibertemis/)' \
  || true)"
if [[ -n "${UNEXPECTED}" ]]; then
  printf '%s\n' "${UNEXPECTED}" >&2
  die "upstream has unexpected modifications; inspect before re-running"
fi

# 6. Apply patches in numeric order. Each patch is checked individually.
shopt -s nullglob
patches=("${PATCHES_DIR}"/*.patch)
[[ ${#patches[@]} -gt 0 ]] || die "no patches found in ${PATCHES_DIR}"

for p in "${patches[@]}"; do
  log "checking + applying ${p##*/}"
  (cd "${UPSTREAM}" && git apply --check "${p}")
  (cd "${UPSTREAM}" && git apply "${p}")
done

# 7. Copy overlay Java + resources into the upstream tree. Overlay resources
#    use unique filenames (vibertemis_*.xml) so they never overwrite
#    upstream's values/strings.xml, values/colors.xml, or any layout file.
#    The launcher icon (mipmap-anydpi-v26/ic_launcher.xml) is the one file
#    the consolidated patch overrides directly.
for src in \
    "${OVERLAY}/src/main/java" \
    "${OVERLAY}/src/main/res" ; do
  if [[ -d "${src}" ]]; then
    rsync -a "${src}/" "${UPSTREAM}/app/src/main/${src##*/}/"
  fi
done

# 8. Tests live under app/src/test.
if [[ -d "${OVERLAY}/src/test" ]]; then
  rsync -a "${OVERLAY}/src/test/" "${UPSTREAM}/app/src/test/"
fi

log "Overlay applied."
log "  upstream : ${UPSTREAM}"
log "  patches  : ${#patches[@]} file(s)"
log "  overlay  : ${OVERLAY}"