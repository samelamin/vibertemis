# Vibertemis Launch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebrand the maintained fork publicly as Vibertemis, publish a truthful upstream comparison and AI-assisted beta story, preserve existing Artemis installation data, rename and push the GitHub fork, and prepare a Reddit beta draft.

**Architecture:** Treat `Vibertemis` as the public display and distribution name while retaining established Artemis IDs, settings keys, executable/project names, and rolling asset filenames as a compatibility layer. Extend the existing fork-identity test into the branding contract, then update runtime/package metadata, repository routes/workflows, and launch documentation in independently verifiable commits.

**Tech Stack:** Qt 6/qmake, QML, Python `unittest`, Flatpak/AppStream metadata, WiX XML, Bash packaging scripts, GitHub Actions, Git/GitHub.

---

## File map

- `packaging/flatpak/tests/test_fork_identity.py`: authoritative public-brand, repository-route, workflow-gate, README-section, and compatibility-ID contract.
- `app/main.cpp`, `app/app.pro`, `app/Info.plist`: visible runtime/macOS/Windows product identity while retaining QSettings and bundle identifiers.
- `app/gui/*.qml`, `app/deploy/linux/*`, `packaging/flatpak/*`: current user-facing product copy and Linux/Flatpak metadata.
- `wix/Artemis/Product.wxs`, `wix/ArtemisSetup/*`, `wix/MoonlightSetup/*`: Windows display strings and fork update URLs without changing upgrade IDs, executable names, registry keys, or installer component IDs.
- `scripts/generate-dmg.sh`, `scripts/verify-macos-bundle.sh`, `.github/workflows/dev-build.yml`: Vibertemis macOS bundle/artifact paths, public release/job language, and renamed-repository publication gate.
- `scripts/build-appimage.sh`, `scripts/build-artemis-arch.bat`, `scripts/generate-artemis-bundle.bat`, `scripts/build-steamlink-app.sh`: safely exposed package labels and artifact names while retaining executable/build-system compatibility.
- `app/backend/autoupdatechecker.cpp`, `tests/autoupdate/tst_autoupdate.cpp`: renamed GitHub release API and deterministic fixtures.
- `README.md`, `docs/DEVELOPMENT.md`, `docs/STEAM_DECK.md`, `.github/ISSUE_TEMPLATE/bug_report.md`: launch story, comparison, tester guidance, renamed routes, and compatibility notes.
- Local Git config and GitHub repository settings: authoritative `origin`, read-only `upstream`, repository rename, branch push, and CI observation.

### Task 1: Establish the branding and compatibility contract

**Files:**
- Modify: `packaging/flatpak/tests/test_fork_identity.py`

- [ ] **Step 1: Add failing public identity assertions**

Require `samelamin/vibertemis` for maintained routes; require the README headings `Why Vibertemis?`, `AI-enhanced development`, `Beta testers wanted`, and `Known validation gaps`; require current Linux/Flatpak names and runtime display metadata to say Vibertemis; reject obsolete `samelamin/artemis` URLs outside historical design/plan files.

- [ ] **Step 2: Add compatibility assertions**

Require `com.artemisdesktop.ArtemisDesktopDev`, `com.artemis_desktop.Artemis`, `QCoreApplication::setApplicationName("Artemis")`, `artemis.pro`, the `artemis` Flatpak command, `art://` support, and legacy rolling asset names to remain.

- [ ] **Step 3: Run the test and verify RED**

Run: `python3 -m unittest vibertemis_packaging_tests.test_fork_identity -v`

Expected: failures identify missing Vibertemis README/metadata/routes while compatibility assertions pass.

- [ ] **Step 4: Commit the failing contract**

Run: `git add packaging/flatpak/tests/test_fork_identity.py && git commit -m "test: define Vibertemis identity contract"`

### Task 2: Apply the visible application identity

**Files:**
- Modify: `app/main.cpp`
- Modify: `app/app.pro`
- Modify: `app/Info.plist`
- Modify: `app/gui/main.qml`
- Modify: `app/gui/SettingsView.qml`
- Modify: `app/deploy/linux/com.artemis_desktop.Artemis.desktop`
- Modify: `app/deploy/linux/com.artemis_desktop.Artemis.appdata.xml`
- Modify: `packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.desktop`
- Modify: `packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.metainfo.xml`
- Modify: `wix/Artemis/Product.wxs`
- Modify: `wix/ArtemisSetup/Bundle.wxs`
- Modify: `wix/ArtemisSetup/RtfTheme.wxl`
- Modify: `wix/MoonlightSetup/Bundle.wxs`
- Modify: `wix/MoonlightSetup/RtfTheme.wxl`
- Modify: `.github/workflows/dev-build.yml`
- Modify: `scripts/build-appimage.sh`
- Modify: `scripts/build-artemis-arch.bat`
- Modify: `scripts/generate-artemis-bundle.bat`
- Modify: `scripts/build-steamlink-app.sh`

- [ ] **Step 1: Set the runtime display name without moving settings**

After constructing `QGuiApplication`, call `QCoreApplication::setApplicationDisplayName("Vibertemis")`. Keep organization/domain and `setApplicationName("Artemis")` unchanged. Change active user-facing Qt/QML messages to Vibertemis but retain explicit `Upstream Artemis` attribution labels.

- [ ] **Step 2: Update platform display metadata**

Use `Vibertemis` for Linux/Flatpak desktop and AppStream names/summaries, macOS bundle/display/package name and network message, Windows file product metadata, installer product/shortcut labels, safely exposed package/artifact labels, and installer UI strings. Preserve IDs, registry/settings paths, internal executable/component identifiers, bundle identifier, build-system input names, and historical AppStream release notes. Keep the macOS executable `Contents/MacOS/Artemis`; package the enclosing bundle as `Vibertemis.app`.

- [ ] **Step 3: Run the identity test toward GREEN**

Run: `python3 -m unittest vibertemis_packaging_tests.test_fork_identity -v`

Expected: branding assertions for application metadata pass; repository/README assertions may remain red until later tasks.

- [ ] **Step 4: Commit public app branding**

Run: `git add app packaging/flatpak wix scripts .github/workflows/dev-build.yml && git commit -m "feat: brand the app as Vibertemis"`

### Task 3: Rename macOS packaging paths safely

**Files:**
- Modify: `packaging/flatpak/tests/test_fork_identity.py`
- Modify: `scripts/generate-dmg.sh`
- Modify: `scripts/verify-macos-bundle.sh`
- Modify: `.github/workflows/dev-build.yml`
- Modify: `docs/DEVELOPMENT.md`

- [ ] **Step 1: Extend the failing contract for macOS paths**

Require `Vibertemis.app`, its compatibility executable `Contents/MacOS/Artemis`, `Vibertemis-VERSION.dmg`, and `vibertemis-macos-ARCH-VERSION` in scripts/workflow/docs; keep `ARTEMIS_MAC_ARCHS` as a compatibility environment variable for existing automation.

- [ ] **Step 2: Run the contract and verify RED**

Run: `python3 -m unittest vibertemis_packaging_tests.test_fork_identity -v`

Expected: macOS path assertions fail on the old Artemis public bundle names.

- [ ] **Step 3: Update package generation and verification**

Change the enclosing app bundle, dSYM label, volume, DMG, mounted-bundle, CI architecture inspection path, upload artifact, and documented local paths consistently while keeping the internal `Artemis` executable. Do not change signing/notarization behavior.

- [ ] **Step 4: Verify locally**

Run shell syntax checks for both scripts, the identity test, qmake/build tests available on macOS, then `ARTEMIS_MAC_ARCHS="$(uname -m)" ./scripts/generate-dmg.sh Release "$(tr -d '\r\n' < app/version.txt)"` and `./scripts/verify-macos-bundle.sh` on the generated app/DMG.

Expected: native package builds, mounts, links, and smoke-launches under the Vibertemis bundle name; if toolchain prerequisites are unavailable, record the exact blocker and require the renamed GitHub CI job before claiming success.

- [ ] **Step 5: Commit macOS package rename**

Run: `git add packaging/flatpak/tests/test_fork_identity.py scripts .github/workflows/dev-build.yml docs/DEVELOPMENT.md && git commit -m "build: package Vibertemis on macOS"`

### Task 4: Move maintained GitHub routes to the renamed fork

**Files:**
- Modify: `app/backend/autoupdatechecker.cpp`
- Modify: `tests/autoupdate/tst_autoupdate.cpp`
- Modify: `app/gui/main.qml`
- Modify: `app/deploy/linux/com.artemis_desktop.Artemis.appdata.xml`
- Modify: `packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.metainfo.xml`
- Modify: `wix/ArtemisSetup/Bundle.wxs`
- Modify: `wix/MoonlightSetup/Bundle.wxs`
- Modify: `.github/workflows/dev-build.yml`
- Modify: `docs/DEVELOPMENT.md`
- Modify: `docs/STEAM_DECK.md`
- Modify: `.github/ISSUE_TEMPLATE/bug_report.md`

- [ ] **Step 1: Run the route contract and verify RED**

Run: `python3 -m unittest vibertemis_packaging_tests.test_fork_identity -v`

Expected: maintained `samelamin/artemis` routes and the old workflow repository gate are reported.

- [ ] **Step 2: Replace maintained distribution/support routes**

Change API, homepage, bug tracker, releases, downloads, workflow badge/gate, clone, and issue-template routes to `samelamin/vibertemis`. Change any remaining visible workflow job names and release titles/notes to Vibertemis while retaining compatibility executable and installer identity names. Keep narrowly allowlisted upstream wiki/repository links explicitly labeled `Upstream Artemis`.

- [ ] **Step 3: Update updater fixtures and run unit tests**

Run the existing Qt autoupdate test target and confirm selected release URLs use `samelamin/vibertemis` while semantic-version behavior is unchanged.

- [ ] **Step 4: Validate workflow and metadata**

Run: `python3 -c 'import yaml; yaml.safe_load(open(".github/workflows/dev-build.yml"))'`, `actionlint .github/workflows/dev-build.yml` when installed, and the existing Flatpak manifest/AppStream tests.

- [ ] **Step 5: Commit repository routes**

Run: `git add .github app packaging/flatpak docs tests wix && git commit -m "build: target the Vibertemis fork"`

### Task 5: Write the marketing-ready README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Run README contract and verify RED**

Run: `python3 -m unittest vibertemis_packaging_tests.test_fork_identity -v`

Expected: required launch headings/comparison/renamed routes are missing.

- [ ] **Step 2: Rewrite the lead and comparison**

Use the approved tagline and attribution. Add the issue-linked `Why Vibertemis?` table using only verified branch improvements. Keep the detailed Steam Deck install commands and legacy Flatpak ID/asset filenames, explaining their compatibility purpose.

- [ ] **Step 3: Add AI and beta sections**

Describe Sam's role, Codex's implementation/testing/documentation role, and Claude only to the extent demonstrated by an actual review. Add tester targets and known validation gaps, including physical Deck HDR/AV1 and unsigned/unnotarized macOS packages. State per-host profiles as the first planned post-beta feature without claiming it exists.

- [ ] **Step 4: Run identity and documentation tests**

Run: `python3 -m unittest vibertemis_packaging_tests.test_fork_identity vibertemis_packaging_tests.test_flatpak_documentation -v`

Expected: all tests pass.

- [ ] **Step 5: Commit launch documentation**

Run: `git add README.md && git commit -m "docs: introduce Vibertemis beta"`

### Task 6: Full verification and independent review

**Files:**
- Modify only files required by review findings.

- [ ] **Step 1: Run the complete affected Python suite**

Run: `python3 -m unittest discover -s packaging/flatpak/tests -p 'test_*.py' -v`

- [ ] **Step 2: Run Qt tests and build checks**

Run refresh-rate and autoupdate test binaries through the repository's existing qmake targets, validate the Flatpak manifest/AppStream metadata, parse/actionlint the workflow, shell-syntax-check changed Unix packaging scripts, and build/package macOS locally where dependencies allow. Inspect Windows batch/WiX changes for preservation of executable, component, registry, and upgrade identities. This task is the local pre-push gate; cross-platform CI observation follows the repository rename and push in Task 7.

- [ ] **Step 3: Audit the final identity boundary**

Search tracked non-historical files for obsolete maintained-fork URLs and active user-facing Artemis branding. Confirm compatibility IDs/settings/project/executable/asset names remain exactly as planned.

- [ ] **Step 4: Request independent code review**

Review the complete diff against `docs/superpowers/specs/2026-07-22-vibertemis-launch-design.md`. Fix every Critical/Important finding and rerun affected verification. Attempt Claude review; disclose authentication failure instead of claiming a Claude review if unavailable.

### Task 7: Rename, push, observe CI, and draft Reddit post

**Files:**
- Local Git configuration only; Reddit draft is delivered in the final handoff, not committed.

- [ ] **Step 1: Rename the GitHub repository**

Rename `samelamin/artemis` to `samelamin/vibertemis` using an authenticated GitHub session. Confirm the repository page and old redirect both resolve.

- [ ] **Step 2: Configure safe remotes**

Set `origin` fetch/push to `https://github.com/samelamin/vibertemis.git`. Add `upstream` fetch URL `https://github.com/wjbeckett/artemis.git` and set its push URL to a deliberately invalid `DISABLED` target. Confirm branch `codex/steam-deck` pushes to origin.

- [ ] **Step 3: Push and observe CI**

Push `codex/steam-deck`, locate the resulting `dev-build.yml` run, and wait for all enabled affected jobs: identity/tests, Windows portable and installer, macOS, Linux, AppImage, Flatpak, Raspberry Pi, and the rolling publisher. Record any intentionally disabled Steam Link job as unverified. Do not describe the beta build as available until the release URL and checks resolve in the renamed repository.

- [ ] **Step 4: Draft the Reddit post**

Deliver a personal draft highlighting transparent AI-assisted revival, Steam Deck/Vibepollo focus, install/release and issue links, requested test matrix, known limitations, credits, and GPLv3 provenance. Do not publish it.
