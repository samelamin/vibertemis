# Fork-owned Steam Deck release design

## Purpose

Make `samelamin/artemis` the authoritative distribution point for this Steam
Deck-focused fork and publish the already-verified Flatpak in a form that is
easy to install and update without downloading a GitHub Actions ZIP.

This design does not submit the application to Flathub, add signing keys, or
replace the existing multi-platform release workflow. Those can follow after
the fork has physical-device acceptance results and a stable release policy.

## Repository identity

Fork-owned user journeys must resolve to `samelamin/artemis`:

- build badges and Actions links;
- clone instructions;
- Releases and download links;
- homepage and bug-report metadata;
- the in-application update API and release buttons;
- Windows installer help/update metadata; and
- issue-template links that refer to fork-owned support.

An automated audit will reject residual `wjbeckett/artemis` URLs unless the
URL is explicitly allowlisted as upstream attribution or upstream-only
documentation. The allowlist must be narrow and documented. It may include the
upstream repository and wiki, but never an updater, release download, badge,
clone command, homepage, or bug-report route used by fork users.

The existing LICENSE and copyright headers remain unchanged. The README will
state that Sam Elamin ported and maintains this Steam Deck-focused fork of
William Beckett's Artemis project, while retaining the existing Moonlight,
Artemis Android, Apollo, and Sunshine credits.

## Rolling Flatpak release

The verified Flatpak build remains the source of truth. A new publishing job
will depend only on `setup-version` and `build-flatpak-dev`, so unrelated macOS,
Windows, AppImage, or Raspberry Pi failures cannot prevent Deck deployment.

The workflow defaults to `permissions: contents: read`. Write permission is
granted only at job scope to jobs that actually publish GitHub releases:

- the existing `create-dev-release` job retains `permissions: contents: write`
  for its versioned tag and multi-platform release; and
- the new Steam Deck publishing job gets `permissions: contents: write` for its
  rolling release.

Build and test jobs remain read-only. The Steam Deck publishing job has both of
these hard gates:

- `github.repository == 'samelamin/artemis'`; and
- `github.ref == 'refs/heads/codex/steam-deck'`.

The job downloads the exact Actions artifact produced by its dependency. It
publishes or replaces assets on the rolling prerelease tag
`steam-deck-latest`:

- `artemis-steam-deck.flatpak`, for direct installation;
- `artemis-steam-deck.flatpak.sha256`, uploaded last; and
- `artemis-steam-deck-bundle.tar.gz`, containing both files as one atomic
  download for users who want manual checksum verification.

The versioned Actions artifact remains available for CI traceability. Release
notes record the source commit, workflow run, version, verification status, and
the fact that assets under this tag are replaced by later builds.

### Publication ordering and verification

The job computes the local SHA-256, uploads the Flatpak, downloads the published
Flatpak through the release API into a fresh directory, and compares its bytes
against the local digest. It only uploads the sidecar and atomic archive after
that comparison succeeds. A failed comparison fails the publish job.

Two independently fetched rolling assets cannot be transactionally atomic.
Documentation therefore tells checksum users to use the atomic archive or
retry a sidecar mismatch during an in-progress replacement. Direct Flatpak
installation remains the shortest path:

```bash
curl -fL https://github.com/samelamin/artemis/releases/download/steam-deck-latest/artemis-steam-deck.flatpak \
  -o ~/Downloads/artemis-steam-deck.flatpak
flatpak install --user --or-update ~/Downloads/artemis-steam-deck.flatpak
```

## Updater behavior

The update checker will use the fork's Releases API. Repository identity must
be centralized where practical instead of duplicating a mutable URL across
logic and tests.

The current checker considers only the first Releases API result, so the
nonnumeric `steam-deck-latest` tag would otherwise mask versioned desktop
releases. The checker will iterate releases in API order, skip the exact rolling
Deck tag and tags that cannot supply at least a numeric major/minor version, and
select the first valid versioned release using the existing version-comparison
policy. This retains development-prerelease support while preventing the Deck
distribution channel from becoming a desktop application version.

A deterministic response fixture will put `steam-deck-latest` first and a
valid `samelamin/artemis` versioned release second, then prove the checker emits
the versioned release. Fixtures will also cover an empty list, only-invalid
tags, and a normal versioned release first. A live schema probe may be used as
non-blocking diagnostics, but tests must not require public network access. The
fork must have a versioned release before documentation claims that automatic
update discovery works end to end.

## README and user documentation

The README lead will clearly identify the fork and upstream provenance. The
Steam Deck section will summarize the delivered work without claiming physical
hardware verification:

- tracked, pinned Flatpak inputs and reproducible CI;
- FFmpeg, libplacebo, SDL3, and SDL2-compat integration;
- Gamescope and Vulkan queue compatibility;
- fractional refresh-rate handling;
- Moonlight port, controller, audio, and dialog fixes;
- Flatpak validation, codec probing, smoke tests, and CI build artifacts;
- the rolling installation/update command; and
- links to the detailed Steam Deck setup and acceptance guide.

The macOS section will be updated by the macOS build-repair subproject. Claims
will distinguish compile/package verification, local launch verification, and
real streaming verification.

## Testing and acceptance

- Parse the workflow as YAML and run `actionlint` when available.
- Run the existing Flatpak manifest tests, validator, codec probe, application
  smoke test, AppStream validation, and dependency inspection.
- Add a regression test or script for fork-owned and allowlisted upstream URLs.
- Add a deterministic updater response-fixture test.
- Confirm a leading `steam-deck-latest` result cannot mask a later valid
  versioned release.
- Confirm the publishing job cannot run outside the fork and branch gates.
- Confirm published bytes match the digest before the sidecar is uploaded.
- Confirm the documented `flatpak install --user --or-update` path works with
  the generated bundle.

## Failure handling

Build failure leaves the previous rolling release untouched. Upload or
post-publish verification failure fails the job and must not upload a new
sidecar. The release notes disclose that the stable URL is rolling, so users
performing manual verification know to retry if publication is in flight.

## Integration order

Implement and test the protocol conversion first, repair and verify the macOS
build second, then reconcile the shared workflow, repository URLs, updater, and
README in the fork-release work. Finish with one combined workflow parse, URL
audit, README claim audit, and CI run because the macOS and release subprojects
both modify `.github/workflows/dev-build.yml` and user-facing build guidance.
