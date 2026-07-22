# macOS Build Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a truthful Apple Silicon Artemis DMG with Qt 6.11.1 and verify the same build on the user's macOS 26.5 machine.

**Architecture:** Pin a separate current Qt for macOS, make the packaging script architecture-aware, replace the duplicated CI build with one clean package path, and verify the deployed bundle rather than the build-tree binary. Local validation uses the same script and checks as CI.

**Tech Stack:** macOS 26, Apple Clang, Qt 6.11.1/qmake, macdeployqt, Homebrew, create-dmg/hdiutil, GitHub Actions.

---

### Task 1: Make DMG generation architecture-truthful

**Files:**
- Modify: `scripts/generate-dmg.sh`
- Create: `scripts/verify-macos-bundle.sh`

- [ ] **Step 1: Write the verification script first**

Create a strict Bash script accepting an app path and optional DMG path. It must verify:

```bash
test -x "$APP_PATH/Contents/MacOS/Artemis"
lipo -archs "$APP_PATH/Contents/MacOS/Artemis"
otool -L "$APP_PATH/Contents/MacOS/Artemis"
```

After creating it, immediately run `chmod +x scripts/verify-macos-bundle.sh`
and preserve the executable mode in git before any direct invocation.

Fail if a deployed binary links directly to `/opt/homebrew`, `/usr/local`, or a missing non-system absolute dependency. Launch the deployed executable with `QT_QPA_PLATFORM=offscreen` and `SDL_VIDEODRIVER=dummy`; exit 0 or surviving a bounded timeout passes, while an immediate non-zero exit fails. If a DMG is provided, mount it into a `mktemp -d` directory, verify the app executable, and detach it in a trap.

Implement the bounded launch without relying on GNU `timeout`, which macOS does
not provide by default: start the executable in the background with its output
captured, wait five seconds, pass if it is still alive, then terminate it and
consume the expected signal-derived status with `wait "$pid" || true` (or an
equivalent temporary `errexit` guard). If it exited before five seconds, collect
its status under an explicit `set +e`/`set -e` guard and fail only when non-zero.

- [ ] **Step 2: Run the script against a nonexistent bundle**

Run: `scripts/verify-macos-bundle.sh /tmp/does-not-exist`

Expected: FAIL with a missing executable diagnostic.

- [ ] **Step 3: Remove the unconditional universal claim**

Add a Bash shebang and strict mode to `generate-dmg.sh`. Configure native architecture by default, while allowing an explicit `ARTEMIS_MAC_ARCHS` value:

```bash
ARTEMIS_MAC_ARCHS=${ARTEMIS_MAC_ARCHS:-$(uname -m)}
qmake "$SOURCE_ROOT/artemis.pro" \
  "QMAKE_APPLE_DEVICE_ARCHS=$ARTEMIS_MAC_ARCHS" \
  "QMAKE_MACOSX_DEPLOYMENT_TARGET=${MACOSX_DEPLOYMENT_TARGET:-14.0}"
```

After deployment, derive `ACTUAL_ARCHS` with `lipo -archs` and write it to `build_info_macos.txt`. Never use “Universal” unless the result contains both `arm64` and `x86_64`.

Before enabling `set -euo pipefail`, make the existing script strict-safe:

- initialize optional signing/notary inputs with `${NAME:-}`;
- quote every path and command argument;
- replace backtick substitutions with `$(...)`;
- use `mkdir -p` for repeatable output setup;
- make cleanup targets explicit and bounded to the build/installer directories;
- replace `find ... | xargs rm` with a form that succeeds when no dSYM exists;
- preserve the current signed and unsigned create-dmg/fallback behavior; and
- preserve the verification script's executable mode from Step 1.

- [ ] **Step 4: Run shell syntax checks**

Run:

```bash
bash -n scripts/generate-dmg.sh
bash -n scripts/verify-macos-bundle.sh
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/generate-dmg.sh scripts/verify-macos-bundle.sh
git commit -m "build: make macOS packages architecture-aware"
```

### Task 2: Repair and simplify macOS CI

**Files:**
- Modify: `.github/workflows/dev-build.yml`

- [ ] **Step 1: Add the macOS Qt pin and read-only default**

Add `QT_VERSION_MACOS: '6.11.1'`. The macOS compile-sanity matrix entry and `build-macos-dev` use it; other platforms keep `QT_VERSION`.

- [ ] **Step 2: Replace duplicated build work**

Keep checkout, Qt setup, version logging, and dependencies. Replace manual MOC, subproject rebuilds, fallback packaging branches, and the second hidden rebuild with this sequence:

```bash
export PATH="$Qt6_DIR/bin:$PATH"
mkdir -p build/tests-refreshrate
cd build/tests-refreshrate
qmake6 ../../tests/refreshrate/refreshrate.pro
make -j"$(sysctl -n hw.logicalcpu)"
./tst_refreshrate
cd "$GITHUB_WORKSPACE"
ARTEMIS_MAC_ARCHS="$(uname -m)" ./scripts/generate-dmg.sh Release "$VERSION"
./scripts/verify-macos-bundle.sh \
  build/build-Release/app/Artemis.app \
  "build/installer-Release/Artemis-$VERSION.dmg"
```

- [ ] **Step 3: Name the artifact from inspected architecture**

Write an output such as `arm64` or `universal` from `lipo -archs`, then upload `artemis-macos-${suffix}-${version}`. Preserve unsigned-development warnings.

- [ ] **Step 4: Preserve release permissions**

When the shared workflow gains default `contents: read`, ensure the existing `create-dev-release` job receives job-level `contents: write`.

- [ ] **Step 5: Validate workflow structure**

Run a YAML parser and `actionlint` if installed. Inspect expressions selecting `QT_VERSION_MACOS` and verify the mac job calls deployment before bundle smoke.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/dev-build.yml
git commit -m "ci: repair Apple Silicon macOS builds"
```

### Task 3: Build and package on this Mac

**Files:**
- Generated only: an ignored temporary Qt/tool directory and `build/`

- [ ] **Step 1: Capture local toolchain facts**

Run `sw_vers`, `xcrun --show-sdk-version`, `clang --version`, `uname -m`, and `xcode-select -p`. Record whether only Command Line Tools are installed.

- [ ] **Step 2: Install isolated Qt 6.11.1**

Use `aqtinstall` in a temporary virtual environment and install desktop
`clang_64` plus `qtmultimedia` into a task-specific directory. Do not replace
the system Qt or repurpose common environment variables:

```bash
artemis_qt_root=$(mktemp -d /tmp/artemis-qt-6.11.1.XXXXXX)
python3 -m venv "$artemis_qt_root/venv"
"$artemis_qt_root/venv/bin/python" -m pip install --upgrade pip aqtinstall
"$artemis_qt_root/venv/bin/python" -m aqt install-qt \
  mac desktop 6.11.1 clang_64 -m qtmultimedia -O "$artemis_qt_root/Qt"
export PATH="$artemis_qt_root/Qt/6.11.1/macos/bin:$PATH"
```

- [ ] **Step 3: Install missing build dependencies**

Use Homebrew only for the declared FFmpeg, Opus, SDL2, SDL2_ttf, and create-dmg requirements that are absent.

- [ ] **Step 4: Initialize recursive submodules and run the package path**

Run the same qmake test, `generate-dmg.sh`, and `verify-macos-bundle.sh` commands used in CI with Qt 6.11.1 first on PATH.

Expected: QTest passes; Artemis compiles; macdeployqt succeeds; the deployed arm64 executable survives the smoke interval; the DMG mounts and contains an executable app.

- [ ] **Step 5: Record artifact identity**

Capture the DMG path, size, SHA-256, `lipo -archs`, `otool -L`, and the exact level of verification. A real host stream remains manual unless one is available during this task.

### Task 4: Document reproducible macOS development builds

**Files:**
- Modify: `README.md` during final reconciliation
- Modify: `docs/DEVELOPMENT.md`

- [ ] **Step 1: Replace stale Qt/Xcode guidance**

Document Qt 6.11.1 for current macOS 26 SDKs, the exact upstream Qt fix, the temporary cross-platform version skew, native architecture naming, and the local commands from Task 3.

- [ ] **Step 2: Separate verification claims**

Label build, package/launch, and real-stream verification independently.

- [ ] **Step 3: Commit documentation with the shared README reconciliation**

Commit `docs/DEVELOPMENT.md` here if independent; leave `README.md` for the fork-release documentation commit so competing edits are reconciled once.
