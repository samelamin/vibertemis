# Steam Deck quick start and safe in-app updater design

Date: 2026-07-23

## Context

Vibertemis already ships a verified rolling Steam Deck Flatpak and has an
update checker for selected non-Linux platforms. The current Steam Deck
documentation is accurate but optimized for validation work rather than a
first-time user. The Flatpak is distributed as a single-file bundle, so it has
no configured application repository and `flatpak update` cannot discover a
new Vibertemis build.

Flatpak recommends repository-backed distribution when applications need
updates. A single-file bundle is still appropriate for this beta, but a running
sandboxed application must not silently grant itself broad host access in order
to replace its own installation.

## Goals

1. Give a first-time Steam Deck owner one obvious, copy/paste installation
   path that does not use `sudo` or require unlocking SteamOS.
2. Let a rolling Steam Deck build detect when its exact source commit has been
   superseded, even when the semantic version core has not changed.
3. Let a user initiate a download from inside Vibertemis.
4. Verify the downloaded Flatpak before it can be handed to an installer.
5. Require an explicit user confirmation in KDE Discover for the actual
   installation.
6. Preserve controller navigation, clear error reporting, and a manual command
   fallback.
7. Keep existing stable-version update checks working for native Windows and
   macOS builds.

## Non-goals

- Silent or unattended Flatpak installation.
- Adding `org.freedesktop.Flatpak` host-command access to the sandbox.
- Running `flatpak-spawn --host` or shell commands from Vibertemis.
- Replacing the Windows or macOS installer flows in this iteration.
- Hosting a signed Flatpak repository or publishing to Flathub in this
  iteration.
- Claiming that the update flow passed on physical Steam Deck hardware before
  the maintainer tests it.

## Chosen user experience

### First installation

The README will link prominently to a dedicated
`docs/STEAM_DECK_QUICK_START.md`. The quick start will:

1. Explain how to enter Desktop Mode and open Konsole.
2. Provide one copy/paste block that creates a temporary directory, downloads
   the atomic rolling archive, extracts it, verifies the archive's maintained
   SHA-256 record with `sha256sum -c`, and stops before installation if
   verification fails.
3. Install or update the verified bundle with `flatpak install --user
   --or-update`, ensure the user Flathub remote exists for the KDE runtime, and
   print the installed application identity.
4. Explain how to launch Vibertemis in Desktop Mode.
5. Explain how to add Vibertemis to Steam and launch it in Gaming Mode.
6. Provide separate copy/paste blocks for manual update, launch, uninstall,
   and basic diagnostics.
7. State explicitly that users must not use `sudo`, disable SteamOS read-only
   protection, or paste commands from issue comments they have not reviewed.

The existing `docs/STEAM_DECK.md` remains the advanced validation and tuning
guide and links back to the quick start.

### In-app update

When Vibertemis starts inside its Flatpak:

1. It determines whether the build belongs to the rolling Steam Deck channel.
2. It requests the `steam-deck-latest` release, fully dereferences its tag, and
   retrieves the release's update manifest from `samelamin/vibertemis`.
3. It validates the manifest and uses the GitHub comparison API to determine
   whether the running commit is an ancestor of the candidate commit.
4. Only a publisher-marked rolling build with a strictly newer descendant
   makes the existing update button visible. Equal, older, and diverged
   candidates are not offered for installation.
5. Activating the button opens a controller-navigable dialog containing the
   current build, available build, download size, and the actions
   **Download update**, **Later**, and **View release**.
6. The download is written safely to the user's Downloads directory with a
   build-specific filename. The Flatpak manifest grants only
   `--filesystem=xdg-download`, not general home-directory or host-command
   access.
7. The app downloads the exact asset identity recorded in the update manifest,
   enforces the recorded byte size, and computes SHA-256 over it.
8. Only an exact size and digest match enables installer handoff.
9. In Desktop Mode, **Open installer** passes a read-only file descriptor for
   the verified inode to `org.freedesktop.portal.OpenURI.OpenFile`. The portal
   request asks the desktop to open the bundle with its configured handler,
   normally KDE Discover; accepting the portal request is not treated as proof
   that installation completed.
10. In Gaming Mode or when the session type cannot be established safely, the
    app preserves the verified file, asks the user to switch to Desktop Mode,
    and atomically records the complete candidate binding in its private data
    directory. It does not attempt a portal handoff. After the app is opened in
    Desktop Mode, it securely reopens that exact file, refetches the release
    binding, and reverifies it before enabling **Open installer**.
11. The dialog also exposes the exact, quoted manual `flatpak install --user
    --or-update` fallback command for Konsole.

The app never reports installation success itself. It tells the user to close
Vibertemis, approve the update in Discover, and relaunch it. On the next launch,
the embedded source commit proves which build is running.

## Update architecture

### Build identity

The build system will embed three independent read-only values:

- `VIBERTEMIS_BUILD_COMMIT`: the complete 40-character source commit;
- `VIBERTEMIS_UPDATE_CHANNEL`: `rolling`, `stable`, or `none`; and
- `VIBERTEMIS_BUILD_SEQUENCE`: a monotonically increasing GitHub Actions run
  identifier for a published rolling build.

Only the rolling publisher workflow may set `VIBERTEMIS_UPDATE_CHANNEL=rolling`
and a nonzero sequence. It supplies the exact checked-out `github.sha`; local,
dirty, unknown, or developer builds use channel `none`, even if their version
string resembles a prerelease. Stable packaged builds retain the existing
semantic-version checker but do not use the rolling Flatpak installer.

These embedded values mark the intended package channel; they do not
cryptographically authenticate a locally installed binary. Because this is an
open-source beta without publisher attestations, a user-built binary can be
compiled with copied marker values. Local binary authenticity is outside this
iteration's threat model. The safety guarantee here is that an updater which
contains internally valid rolling metadata accepts only a release asset bound
to the maintained repository, a descendant commit, and the verified manifest.

The application exposes these values through a read-only `--build-info`
machine-readable command and to the update backend. CI runs the built Flatpak
with this command and fails publication unless the embedded commit, channel,
sequence, application ID, and version match the workflow source and tag.

### Platform and channel policy

| Package/runtime | Automatic check | Download/install offer |
| --- | --- | --- |
| CI rolling Steam Deck Flatpak | Rolling manifest and ancestry | Verified Flatpak download and explicit Desktop Mode handoff |
| Stable Windows package | Existing semantic release check | Existing release-page behavior |
| Stable macOS package | Existing semantic release check | Existing release-page behavior |
| Steam Link package | Existing semantic release check | Existing release-page behavior |
| Linux AppImage | Existing semantic release check | Existing release-page behavior |
| Other Linux/Flatpak package | Stable check only when already supported | No rolling Flatpak install offer |
| Local, dirty, unknown, or channel `none` build | Optional manual stable check | Never offers a rolling install |

An ahead build, a candidate that is not a descendant, or an API result that
cannot prove ancestry is reported only in diagnostics. It never becomes an
installable update. Full commit hashes are used for all decisions; abbreviated
hashes are display-only.

### Publication and provenance contract

The rolling workflow publishes an `artemis-steam-deck-update.json` manifest
whose schema is versioned and includes:

- repository and application ID;
- source commit and monotonic build sequence;
- release and tag identity;
- exact Flatpak asset API ID, expected filename, byte size, and SHA-256; and
- publication timestamp.

Publication happens in this order:

1. Build the Flatpak from the checked-out full commit and inspect its embedded
   build information.
2. Upload the Flatpak to the rolling release, query GitHub for its resulting
   immutable asset ID, and download that exact asset back for digest and size
   verification.
3. Generate and upload the update manifest that binds the commit and sequence
   to that exact asset identity.
4. Upload and verify the human-facing checksum and atomic archive companions.
5. Move `steam-deck-latest` to the fully dereferenced source commit only after
   all release assets and the manifest have passed verification.

Annotated tags are dereferenced until a commit object is reached. A tag cycle,
unexpected object type, or commit mismatch fails publication. Dirty worktrees
and non-CI attempts cannot create rolling-channel artifacts.

### Release parsing

Pure, unit-testable release parsing will be separated from networking. It will
return:

- channel and release label;
- release page URL;
- fully dereferenced source commit;
- manifest schema, build sequence, and publication timestamp;
- release, tag, manifest, and Flatpak asset identities;
- Flatpak asset API URL, expected size, and SHA-256; and
- expected Flatpak filename.

The parser rejects missing fields, non-HTTPS URLs, unexpected GitHub
repository/asset paths, duplicate matching assets, malformed or abbreviated
commit IDs, unsupported manifest schemas, non-monotonic sequences, and
ambiguous releases. It must not trust the release's historical
`target_commitish` field for a moving tag.

The checker captures the release ID, tag object identity, manifest asset ID,
Flatpak asset ID, sizes, and update timestamps before downloading. After the
Flatpak is verified it refetches the release, tag, and manifest metadata. Any
identity or binding change is treated as a transient publisher-in-progress
state: the file is not handed off, and the user can retry.

### Network and state

`AutoUpdateChecker` becomes a persistent update service rather than destroying
its network manager after the initial check. Its explicit states and permitted
transitions are:

| State | Permitted next states |
| --- | --- |
| `Idle` | `RestoringPending` when a record exists, otherwise `Checking` |
| `RestoringPending` | `ReadyForDesktop`, `ReadyToHandOff`, `NoUpdate`, `Checking`, `RestoreError`, `Cancelled` |
| `Checking` | `NoUpdate`, `Available`, `CheckError`, `Cancelled` |
| `NoUpdate` | `Checking` |
| `Available` | `Downloading`, `Checking`, `Cancelled` |
| `Downloading` | `Verifying`, `DownloadError`, `Cancelled` |
| `Verifying` | `ReadyForDesktop`, `ReadyToHandOff`, `VerificationError`, `Cancelled` |
| `ReadyForDesktop` | `Verifying`, `Checking`, `Cancelled` |
| `ReadyToHandOff` | `HandingOff`, `Verifying`, `Checking`, `Cancelled` |
| `HandingOff` | `HandOffRequested`, `HandOffError` |
| `HandOffRequested` | `Checking`, `Verifying` |
| `CheckError` | `Checking` |
| `DownloadError` | `Available`, or `Checking` when the captured candidate expired |
| `VerificationError` | `Available`, or `Checking` when the captured candidate expired |
| `HandOffError` | `Verifying`, then `ReadyToHandOff` after fresh verification |
| `RestoreError` | `RestoringPending`, `Checking`, `Cancelled` |
| `Cancelled` | `Checking` |

`HandOffRequested` means only that the desktop portal accepted the request.
The application never infers installation completion. The following errors
retain a user-readable message and allow a valid retry:

- offline or HTTP failure;
- API rate limit;
- malformed release metadata;
- unprovable ancestry or transient publication;
- unavailable or unwritable Downloads directory;
- insufficient free space;
- cancelled download;
- size or manifest validation failure;
- digest mismatch; and
- installer handoff failure.

Only one check or download may run at once. Every request requires a successful
2xx response. Redirects have a small fixed hop limit and may target only
approved GitHub API and release-asset CDN hosts; authorization headers are
never forwarded across origins. Connect, idle-read, and overall request
timeouts prevent indefinite stalls. Release/tag responses and the JSON update
manifest have strict byte caps. The Flatpak must match the exact manifest size,
and the app checks that the destination has enough free space plus a safety
margin before downloading.

Downloads use an application-owned randomized temporary file created
exclusively in the Downloads directory. The implementation rejects symlinks,
does not follow an existing destination, and keeps a read-only descriptor for
the verified inode open through portal handoff. The final display filename
contains a short commit but the complete hash remains in stored metadata.
Existing final files are reused only after a fresh size and digest check.
Cancellation and validation failure remove untrusted files; startup removes
only stale `.part` files bearing the application's own unguessable naming
prefix.

### Pending update persistence

A verified download that must survive a Gaming-to-Desktop mode switch is
tracked by one versioned pending-update record in the Flatpak's private
application-data directory. The record is written atomically with `QSaveFile`
and contains:

- schema version and canonical local path;
- candidate full commit and sequence;
- release ID and fully dereferenced tag identity;
- manifest and Flatpak asset IDs;
- expected filename, exact byte size, and SHA-256; and
- the last validated release/asset update timestamps.

The record never makes its contents trusted. On startup, the app validates its
schema and field formats, resolves the current Downloads directory, proves that
the canonical path is a regular non-symlink file beneath that directory, and
opens it read-only without following links. It then refetches the maintained
release, recursively resolves its tag, reparses the update manifest, confirms
every captured identity is unchanged, and hashes the same open descriptor.
Only that complete sequence can restore `ReadyToHandOff`.

A missing, replaced, malformed, or stale file/record is invalidated without
opening it, clears the record, and transitions from `RestoringPending` to a
fresh `Checking` state. If the running full commit already equals the pending
candidate, restoration clears the completed record and transitions to
`NoUpdate`. Transient network or filesystem failures use `RestoreError` and
preserve enough validated state to retry. The record is also cleared when the
user explicitly cancels and removes the pending download or the release
binding is invalidated. Portal acceptance alone does not clear it, so a failed
or abandoned installer remains recoverable. Cleanup deletes only the exact
app-owned file after repeating the same containment and no-follow checks.

### SteamOS session classification

Installer handoff requires a positive Desktop Mode classification. Environment
signals are evaluated with Gaming Mode taking precedence:

| Signals visible inside the Flatpak | Classification |
| --- | --- |
| Nonempty `GAMESCOPE_WAYLAND_DISPLAY`, or `XDG_CURRENT_DESKTOP` contains `gamescope` case-insensitively | Gaming Mode |
| No Gaming signal, `XDG_CURRENT_DESKTOP` contains `KDE`, `KDE_FULL_SESSION=true`, and `XDG_SESSION_TYPE` is `wayland` or `x11` | Desktop Mode |
| Conflicting values, missing values, or any other combination | Unknown |

Gaming and unknown classifications may download and verify but may not call the
portal. Unit fixtures cover captured SteamOS Desktop and Gaming environments,
including missing and deliberately conflicting inputs. During physical
acceptance testing the maintainer records redacted environment snapshots from
both modes; if SteamOS exposes different signals, the detector and fixtures
must be corrected before calling the feature hardware-tested.

### UI

The update dialog will use existing navigable dialog/button patterns. It must
be usable with keyboard, touchscreen, and controller. Progress is determinate
when GitHub provides a content length and indeterminate otherwise.

The UI will distinguish:

- **Update available**: no bytes downloaded;
- **Downloading**: progress and cancel;
- **Verification failed**: no install action;
- **Ready for Desktop Mode**: verified file retained, instructions to switch
  modes, and no handoff action;
- **Ready to open installer**: verified digest and installer/manual options;
- **Installer requested**: handoff requested, with no success claim; and
- **No update / check failed**: quiet startup behavior, with diagnostic logs
  and a controller-accessible **Check for updates** action in Settings/About.

Background checks do not interrupt a stream or open a modal dialog
automatically. The user activates the update button to see the dialog. Initial
focus, A-button activation, B-button dismissal, cancel, retry, and focus
restoration follow the existing controller navigation conventions.

## Security boundaries

- HTTPS with no-less-safe redirects remains mandatory.
- Release, manifest, comparison, and asset URLs are restricted to the
  maintained GitHub repository and approved GitHub asset delivery hosts.
- The versioned manifest binds the complete commit, sequence, asset identity,
  filename, byte size, and SHA-256.
- Exact size and digest comparison, release identity revalidation, and session
  mode checks occur before installer handoff.
- Unverified files are deleted and never opened.
- The app receives only Downloads-directory write access.
- Vibertemis never invokes a host shell, `sudo`, `flatpak-spawn --host`, or the
  Flatpak service D-Bus API.
- The manual fallback is a display-only backend property. It POSIX
  double-quote escapes the verified path before QML can copy it; QML neither
  constructs nor executes the command.
- Desktop handoff uses only `org.freedesktop.portal.OpenURI.OpenFile` with the
  verified read-only file descriptor. A `file://` URL is not used.
- Installation remains visible and confirmable in the configured desktop
  bundle handler, expected to be Discover on SteamOS.

## Documentation changes

- Add a top-level README callout: **Installing on Steam Deck? Start here.**
- Replace the current long README install treatment with a concise quick-start
  command and links to the beginner and advanced guides.
- Add `docs/STEAM_DECK_QUICK_START.md`.
- Link the advanced guide back to the quick start.
- Document the in-app update flow and the manual fallback.
- Explain the compatibility application ID and `artemis-steam-deck.*` filenames
  once, in plain language.
- Include recovery steps for a failed download, Discover not opening, and
  reverting to the manual command.
- Explain that Gaming Mode can download and verify an update but installation
  is deliberately completed after switching to Desktop Mode.

## Test strategy

### C++ unit tests

- stable semantic release selection remains unchanged;
- rolling channel detection rejects local, dirty, unknown, malformed, and
  internally inconsistent build identities;
- full commit validation and display-only abbreviation;
- equality, descendant, ahead, diverged, rollback, and unprovable ancestry;
- recursive moving-tag dereferencing and invalid tag-object rejection;
- manifest schema and exact asset-ID binding;
- valid asset and sidecar selection;
- rejection of foreign repositories and non-HTTPS URLs;
- duplicate/missing asset rejection;
- redirect host/hop policy, response caps, timeouts, and exact byte count;
- free-space rejection;
- SHA-256 match and mismatch;
- exclusive temporary creation, symlink rejection, existing-file
  reverification, and scoped stale-part cleanup;
- publisher race detection after release/tag/manifest revalidation;
- `.part` cleanup on cancellation or failure;
- pending record restore, missing/replaced file, malformed or tampered record,
  and publication changes across a mode switch;
- restart transitions for valid, stale, malformed, missing, and replaced
  pending downloads;
- Desktop, Gaming, and unknown-session behavior;
- captured session fixtures, with Gaming precedence for conflicting signals;
- check, download, verification, and handoff-origin retry transitions;
- portal handoff uses the still-open descriptor for the verified inode; and
- no ready-to-install transition before verification.

### QML and documentation contract tests

- update dialog exposes controller-navigable actions;
- install action is disabled before verification;
- A activates and B dismisses/cancels with focus restored;
- Settings/About exposes a manual **Check for updates** action;
- user-facing text never promises a silent or completed installation;
- README links to the beginner guide;
- beginner guide contains the maintained rolling URL and exact application ID;
- first-install commands verify the atomic archive before calling Flatpak;
- a corrupted first-install download prevents `flatpak install`;
- copy/paste blocks use `--user --or-update` and never use `sudo`;
- advanced guide links back to the quick start; and
- all documented Flatpak run commands match the manifest application ID.

### Package and CI checks

- Flatpak manifest validation permits only `xdg-download` as the new filesystem
  access.
- Existing offscreen Flatpak startup test still passes.
- Native updater tests pass on macOS and Linux compile-sanity jobs.
- The built Flatpak's `--build-info` output exactly matches CI source identity.
- Local and dirty builds cannot identify themselves as rolling artifacts.
- The rolling publisher re-downloads the exact uploaded asset ID and verifies
  its size and digest before generating the update manifest.
- The workflow verifies every companion, publishes the tag last, and rejects
  tag/asset/manifest commit mismatches.

## Acceptance criteria

### Ready for maintainer hardware testing

1. All unit, documentation, manifest, and existing packaging tests pass.
2. CI produces the Flatpak and every enabled platform build successfully.
3. A local mocked update proves that a digest mismatch cannot enable installer
   handoff.
4. A local mocked update proves that a mutable-release race, symlink swap, or
   non-descendant commit cannot enable installer handoff.
5. The published rolling release contains a mutually consistent update
   manifest, Flatpak, checksum sidecar, atomic archive, and tag.
6. The beginner install script rejects a deliberately corrupted archive before
   invoking Flatpak.
7. Desktop and Gaming Mode policies are covered by automated state tests.
8. The beginner guide can be followed using only Desktop Mode, Konsole, and
   copy/paste.

### Ready to market as Steam Deck tested

The maintainer records results from a physical Steam Deck for:

1. a clean first install in Desktop Mode;
2. launch and controller navigation in Desktop and Gaming Mode;
3. download and verification in Gaming Mode;
4. mode switch, fresh reverification, and Discover handoff in Desktop Mode;
5. update confirmation, relaunch, and displayed build identity; and
6. recovery through the documented manual command.

LCD or OLED hardware not actually exercised is labeled untested rather than
inferred from the other model. Until this checklist is recorded, documentation
says “ready for Steam Deck testing,” not “Steam Deck tested.”

## Later improvement

After the beta, migrate from single-file bundles to a signed hosted Flatpak
repository and `.flatpakref`. That lets Discover and `flatpak update` use
Flatpak's native update model and efficient deltas. The in-app checker can then
be simplified to a notification and Discover handoff instead of downloading a
whole bundle.

## Primary references

- <https://docs.flatpak.org/en/latest/single-file-bundles.html>
- <https://docs.flatpak.org/en/latest/repositories.html>
- <https://docs.flatpak.org/en/latest/hosting-a-repository.html>
- <https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.OpenURI.html>
- <https://doc.qt.io/qt-6/qdbusunixfiledescriptor.html>
