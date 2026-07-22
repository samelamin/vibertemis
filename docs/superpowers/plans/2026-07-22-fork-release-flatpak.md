# Fork Release and Flatpak Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `samelamin/artemis` the fork-owned update and distribution source and publish the verified Steam Deck Flatpak at a stable release URL.

**Architecture:** Add deterministic tests for repository identity and release selection, migrate user-facing routes, then add a narrowly permissioned rolling publisher that consumes the existing Flatpak artifact and verifies published bytes. Reconcile README and shared workflow changes last.

**Tech Stack:** Qt 6 Core/Network/JSON, QTest, Python unittest, GitHub Actions, GitHub CLI, Flatpak, Markdown/AppStream/WiX metadata.

---

### Task 1: Make release selection testable

**Files:**
- Create: `app/backend/releaseversionselector.h`
- Create: `app/backend/releaseversionselector.cpp`
- Modify: `app/backend/autoupdatechecker.cpp`
- Modify: `app/app.pro`
- Create: `tests/autoupdate/autoupdate.pro`
- Create: `tests/autoupdate/tst_autoupdate.cpp`
- Modify: `tests/tests.pro`

- [ ] **Step 1: Write failing release-array tests**

Create fixtures for:

```json
[
  {"tag_name":"steam-deck-latest","html_url":"https://github.com/samelamin/artemis/releases/tag/steam-deck-latest"},
  {"tag_name":"v6.1.1","html_url":"https://github.com/samelamin/artemis/releases/tag/v6.1.1"}
]
```

Require selection of `6.1.1`, not the rolling tag. Add normal-first, empty, only-invalid, missing URL, and development-tag cases. A valid tag must have numeric major and minor components after an optional leading `v`; later prerelease components retain existing comparison behavior.

- [ ] **Step 2: Run the test and verify failure**

Run qmake/make for `tests/autoupdate/autoupdate.pro`.

Expected: FAIL because `ReleaseVersionSelector` does not exist.

- [ ] **Step 3: Implement the pure selector**

Expose a small value result containing `valid`, normalized `version`, and URL. Iterate the array in API order, skip exact `steam-deck-latest`, reject tags without numeric major/minor, and return the first valid versioned release.

- [ ] **Step 4: Route the checker through the selector**

Replace direct `releasesArray[0]` access. Point the request at `https://api.github.com/repos/samelamin/artemis/releases`. Preserve HTTPS, redirect, platform gating, and current version comparison.

- [ ] **Step 5: Run both Qt test projects**

Expected: updater selector and refresh tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/backend app/app.pro tests
git commit -m "fix: select versioned releases from the fork"
```

### Task 2: Define and enforce fork-owned URLs

**Files:**
- Create: `packaging/flatpak/tests/test_fork_identity.py`
- Modify: `packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.metainfo.xml`
- Modify: `app/deploy/linux/com.artemis_desktop.Artemis.appdata.xml`
- Modify: `app/gui/main.qml`
- Modify: `wix/ArtemisSetup/Bundle.wxs`
- Modify: `wix/MoonlightSetup/Bundle.wxs`
- Modify: `.github/ISSUE_TEMPLATE/*` where present
- Modify: `docs/DEVELOPMENT.md`
- Modify: `README.md` in Task 4

- [ ] **Step 1: Write the failing URL audit**

Scan tracked runtime, packaging, workflow, installer, support, and user-facing
documentation files for `wjbeckett/artemis`. Exclude historical implementation
records under `docs/superpowers/` from the enforcement surface. Allow only
explicit upstream attribution in README and upstream wiki help routes. Fail any
upstream updater, release, homepage, badge, clone, or issue route. Also assert
the updater API, AppStream homepage/bugtracker, QML release action, WiX update
URL, badge, clone, and download links use `samelamin/artemis`.

- [ ] **Step 2: Run Python tests and verify failure**

Run: `python3 -m unittest discover -s packaging/flatpak/tests -p 'test_*.py' -v`

Expected: new fork-identity cases fail on current URLs.

- [ ] **Step 3: Migrate fork-owned routes**

Update releases, updater, homepage, issues, badges, clone, and download links. Keep upstream repository attribution. Keep upstream wiki links only when the fork has no equivalent and label them as upstream documentation.

- [ ] **Step 4: Run URL and AppStream validation**

Expected: Python audit passes; both metadata files remain well-formed; Flatpak AppStream validation passes in the existing build environment.

- [ ] **Step 5: Commit**

```bash
git add packaging/flatpak/tests packaging/flatpak/*.xml app/deploy app/gui/main.qml wix .github/ISSUE_TEMPLATE docs/DEVELOPMENT.md
git commit -m "chore: point distribution routes at the fork"
```

### Task 3: Add the rolling Steam Deck publisher

**Files:**
- Modify: `.github/workflows/dev-build.yml`
- Extend: `packaging/flatpak/tests/test_fork_identity.py`

- [ ] **Step 1: Add failing workflow contract assertions**

Require workflow default `contents: read`, job-level `contents: write` on both `create-dev-release` and the new publisher, exact fork/branch gates, dependency on `build-flatpak-dev`, stable asset names, post-upload download verification, and sidecar upload after verification.

- [ ] **Step 2: Add job permissions safely**

Set workflow default to read. Preserve write permission for `create-dev-release`. Add `publish-steam-deck-release` with write permission and this gate:

Set its dependency list explicitly so every referenced output exists:

```yaml
needs: [setup-version, build-flatpak-dev]
```

```yaml
if: >-
  github.repository == 'samelamin/artemis' &&
  github.ref == 'refs/heads/codex/steam-deck' &&
  needs.setup-version.outputs.should_build == 'true'
```

- [ ] **Step 3: Prepare stable assets from the exact artifact**

Download `artemis-flatpak-${version}`, rename the Flatpak, compute SHA-256, and create `artemis-steam-deck-bundle.tar.gz` containing the Flatpak and sidecar.

- [ ] **Step 4: Publish and verify in safe order**

Create the prerelease if absent. Upload only the Flatpak with `--clobber`, download it via `gh release download` into a clean directory, and compare its SHA-256 to the local digest. Only after that succeeds upload the sidecar and atomic archive. Update release notes with version, commit, run URL, verification, and rolling-replacement disclosure.

- [ ] **Step 5: Parse and lint the workflow**

Run Python/YAML parsing, `actionlint` when available, fork-identity tests, and `git diff --check`.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/dev-build.yml packaging/flatpak/tests/test_fork_identity.py
git commit -m "ci: publish the verified Steam Deck Flatpak"
```

### Task 4: Rewrite README ownership and build guidance

**Files:**
- Modify: `README.md`
- Modify: `docs/STEAM_DECK.md`

- [ ] **Step 1: Rewrite the lead and attribution**

State that Sam Elamin ported and maintains this Steam Deck-focused fork of `wjbeckett/artemis`. Retain LICENSE/copyright provenance and Moonlight, Artemis Android, Apollo, Sunshine, and upstream Artemis credits.

- [ ] **Step 2: Document delivered Steam Deck work**

List pinned Flatpak inputs, FFmpeg/libplacebo/SDL compatibility, Gamescope and Vulkan work, fractional refresh and Vibepollo x100 metadata, Moonlight port/controller/audio/dialog fixes, and CI validation. Do not claim physical Deck or live Vibepollo verification.

- [ ] **Step 3: Add install/update and macOS instructions**

Include the stable curl plus `flatpak install --user --or-update` command, checksum/atomic archive guidance, rolling replacement note, current Mac Qt requirements, truthful Apple Silicon artifact naming, and unsigned DMG notes.

- [ ] **Step 4: Re-run URL and claim audits**

Expected: only allowlisted upstream references remain and all verification claims match actual test evidence.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/STEAM_DECK.md docs/DEVELOPMENT.md
git commit -m "docs: document the Steam Deck port and fork builds"
```

### Task 5: Full integration, CI, release, and push

**Files:**
- Modify only files required by verified review findings

- [ ] **Step 1: Run all deterministic local tests**

Run both QTest projects, all Flatpak Python tests, manifest validation, AppStream checks available locally, shell syntax, workflow parsing/actionlint, URL audit, and `git diff --check`.

- [ ] **Step 2: Run macOS package verification**

Build Qt 6.11.1 locally, generate the native DMG, verify bundle/dependencies, mount it, record SHA-256, and launch it under the bounded smoke test.

- [ ] **Step 3: Re-run the tracked Flatpak build or rely on unchanged green build inputs**

If packaging inputs changed beyond metadata/workflow, rebuild and run codec, dependency, AppStream, refresh-test, and smoke checks before bundling.

- [ ] **Step 4: Request final code review**

Review protocol edge cases, macOS deployment, update selection, workflow permissions, release ordering, fork URLs, and documentation claims. Fix findings and rerun affected checks.

- [ ] **Step 5: Push and follow CI to terminal status**

Push `codex/steam-deck`, inspect every relevant GitHub Actions job, fix and push until macOS compile/package and Flatpak build/publisher are green. Confirm the rolling release assets exist and the published Flatpak re-download hash matches.

- [ ] **Step 6: Report deliverables honestly**

Provide branch/commits, local DMG path and SHA-256, rolling Flatpak URL and checksum, CI run, install command, and any remaining real-Deck/live-host manual acceptance items.
