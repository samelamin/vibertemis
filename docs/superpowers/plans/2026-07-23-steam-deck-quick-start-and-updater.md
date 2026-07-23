# Steam Deck Quick Start and Updater Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a dummy-proof, checksum-first Steam Deck installation guide and a Steam Deck rolling Flatpak updater that downloads and verifies updates in-app while leaving installation visible and user-confirmed in Desktop Mode.

**Architecture:** Preserve the current semantic updater for native packages and add a rolling-Flatpak path selected only by explicit CI build metadata. Separate pure release parsing, file verification/persistence, network orchestration, and Linux portal handoff so each boundary can be tested without GitHub or a desktop session. Publish a versioned update manifest that binds one full source commit and CI sequence to one exact GitHub asset ID, size, and SHA-256.

**Tech Stack:** Qt 5/6 Core, Network, QML, Test, and Linux D-Bus; qmake; Python 3 unittest; Flatpak/flatpak-builder; xdg-desktop-portal; GitHub Actions and GitHub CLI; Markdown.

---

## File structure

New focused units:

- `app/backend/buildinfo.{h,cpp}`: compile-time build identity, validation, display text, and machine-readable JSON.
- `app/backend/updateresult.h`: shared typed success/error result used at
  parser, filesystem, network, restore, and portal boundaries.
- `app/backend/rollingupdateparser.{h,cpp}`: pure GitHub release, tag, comparison, and update-manifest parsing plus URL policy.
- `app/backend/steamdecksession.{h,cpp}`: conservative Desktop/Gaming/unknown session classification.
- `app/backend/pendingupdate.{h,cpp}`: secure download path handling, SHA-256 verification, and atomic pending-record persistence.
- `app/backend/updatestatemachine.{h,cpp}`: pure state/event transition
  reducer shared by the service and exhaustive transition tests.
- `app/backend/desktopinstallerportal.{h,cpp}`: Linux-only `OpenURI.OpenFile` request using a verified read-only descriptor.
- `app/gui/UpdateDialog.qml`: controller-navigable update states and actions.
- `packaging/flatpak/prepare-ci-manifest.py`: validated injection of build identity into a temporary Flatpak manifest.
- `packaging/flatpak/generate-update-manifest.py`: deterministic rolling manifest generation from exact GitHub release/asset metadata.
- `packaging/flatpak/tests/test_updater_packaging.py`: build-identity, permissions, workflow, manifest, documentation, and QML contracts.
- `docs/STEAM_DECK_QUICK_START.md`: beginner install/update/recovery guide.

Existing files remain responsible for:

- `app/backend/autoupdatechecker.{h,cpp}`: persistent network/state orchestration and the existing stable-version path.
- `app/backend/releaseversionselector.{h,cpp}`: unchanged stable semantic release selection.
- `app/main.cpp`: handle the exact `--build-info` request before SDL or
  `QGuiApplication` initialization.
- `app/gui/main.qml` and `app/gui/SettingsView.qml`: update entry points and dialog ownership.
- `app/app.pro`, `app/qml.qrc`, `tests/autoupdate/autoupdate.pro`: compile/link the new units.
- `packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.json`: grant only `xdg-download` as the new filesystem permission.
- `.github/workflows/dev-build.yml`: embed/inspect identity and publish the bound rolling release from `main`.
- `README.md` and `docs/STEAM_DECK.md`: route beginners to the quick start and keep advanced tuning separate.

### Task 1: Add trustworthy build identity and `--build-info`

**Files:**
- Create: `app/backend/buildinfo.h`
- Create: `app/backend/buildinfo.cpp`
- Modify: `app/app.pro`
- Modify: `app/main.cpp`
- Modify: `tests/autoupdate/autoupdate.pro`
- Modify: `tests/autoupdate/tst_autoupdate.cpp`

- [ ] **Step 1: Write failing build-identity tests**

Add a `BuildInfoTest` section to `tests/autoupdate/tst_autoupdate.cpp` that
requires:

```cpp
QCOMPARE(BuildInfo::commit(), QString(40, QLatin1Char('a')));
QCOMPARE(BuildInfo::channel(), BuildInfo::RollingChannel);
QCOMPARE(BuildInfo::sequence(), quint64(1234));
QVERIFY(BuildInfo::isInternallyConsistent());

const QJsonObject json = BuildInfo::toJson();
QCOMPARE(json.value("schema").toInt(), 1);
QCOMPARE(json.value("applicationId").toString(),
         QStringLiteral("com.artemisdesktop.ArtemisDesktopDev"));
QCOMPARE(json.value("commit").toString(), QString(40, QLatin1Char('a')));
QCOMPARE(json.value("channel").toString(), QStringLiteral("rolling"));
QCOMPARE(json.value("sequence").toString(), QStringLiteral("1234"));
```

Add data rows against a pure `BuildInfo::validate(const Identity &)` function
for `none/0/unknown`, malformed commits, rolling with sequence zero,
non-rolling with a nonzero sequence, and a mismatched application ID.
The test project defines a valid rolling fixture:

```qmake
DEFINES += VIBERTEMIS_BUILD_COMMIT=\\\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\\\"
DEFINES += VIBERTEMIS_UPDATE_CHANNEL=\\\"rolling\\\"
DEFINES += VIBERTEMIS_BUILD_SEQUENCE=1234
DEFINES += VIBERTEMIS_APPLICATION_ID=\\\"com.artemisdesktop.ArtemisDesktopDev\\\"
```

- [ ] **Step 2: Run the focused test and verify failure**

Run:

```bash
mkdir -p build/tests-autoupdate
cd build/tests-autoupdate
qmake6 ../../tests/autoupdate/autoupdate.pro
make -j"$(sysctl -n hw.logicalcpu 2>/dev/null || nproc)"
./tst_autoupdate buildIdentity
```

Expected: compilation fails because `BuildInfo` does not exist.

- [ ] **Step 3: Implement the immutable build-info value**

Declare:

```cpp
class BuildInfo
{
public:
    enum Channel { NoChannel, StableChannel, RollingChannel };
    struct Identity {
        QString commit;
        Channel channel;
        quint64 sequence;
        QString applicationId;
        QString version;
    };
    static Identity current();
    static bool validate(const Identity &identity);
    static QString commit();
    static Channel channel();
    static QString channelName();
    static quint64 sequence();
    static QString applicationId();
    static QString version();
    static bool isInternallyConsistent();
    static QJsonObject toJson();
};
```

Use compile-time defaults in `app/app.pro`:

```qmake
isEmpty(VIBERTEMIS_BUILD_COMMIT): VIBERTEMIS_BUILD_COMMIT = $$(VIBERTEMIS_BUILD_COMMIT)
isEmpty(VIBERTEMIS_BUILD_COMMIT): VIBERTEMIS_BUILD_COMMIT = unknown
isEmpty(VIBERTEMIS_UPDATE_CHANNEL): VIBERTEMIS_UPDATE_CHANNEL = $$(VIBERTEMIS_UPDATE_CHANNEL)
isEmpty(VIBERTEMIS_UPDATE_CHANNEL): VIBERTEMIS_UPDATE_CHANNEL = none
isEmpty(VIBERTEMIS_BUILD_SEQUENCE): VIBERTEMIS_BUILD_SEQUENCE = $$(VIBERTEMIS_BUILD_SEQUENCE)
isEmpty(VIBERTEMIS_BUILD_SEQUENCE): VIBERTEMIS_BUILD_SEQUENCE = 0
isEmpty(VIBERTEMIS_APPLICATION_ID): VIBERTEMIS_APPLICATION_ID = $$(VIBERTEMIS_APPLICATION_ID)
isEmpty(VIBERTEMIS_APPLICATION_ID): VIBERTEMIS_APPLICATION_ID = com.artemis_desktop.Artemis

DEFINES += VIBERTEMIS_BUILD_COMMIT=\\\"$$VIBERTEMIS_BUILD_COMMIT\\\"
DEFINES += VIBERTEMIS_UPDATE_CHANNEL=\\\"$$VIBERTEMIS_UPDATE_CHANNEL\\\"
DEFINES += VIBERTEMIS_BUILD_SEQUENCE=$$VIBERTEMIS_BUILD_SEQUENCE
DEFINES += VIBERTEMIS_APPLICATION_ID=\\\"$$VIBERTEMIS_APPLICATION_ID\\\"
```

Only the exact combination `rolling`, a 40-character lowercase hex commit,
positive sequence, and compatibility Flatpak ID is rolling-consistent.
`stable` requires sequence zero. `none` accepts `unknown` or a clean full commit
and always has sequence zero. The API marks publisher intent; it does not claim
cryptographic binary authentication.

- [ ] **Step 4: Add the CLI result before any GUI initialization**

At the start of `main()`, after setting the static application version/name but
before display detection, path initialization, SDL subsystem initialization, or
`QGuiApplication`, recognize only the exact argument vector
`<binary> --build-info`. Construct a short-lived `QCoreApplication`, serialize
compact JSON with a trailing newline, and return. This makes the command safe
inside a headless Flatpak. Keep `--version`, positional actions, and
unknown-option behavior unchanged. On Windows, perform the existing
parent-console attachment/standard-handle setup before writing build-info so
the GUI-subsystem executable produces capturable CI output; factor that setup
into a helper reused by the normal startup path.

- [ ] **Step 5: Run build-info and regression tests**

Run the updater test binary, then build the application explicitly in a shadow
directory and invoke the platform-correct executable:

```bash
./build/tests-autoupdate/tst_autoupdate
mkdir -p build/native-identity
cd build/native-identity
qmake6 ../../artemis.pro CONFIG+=release
make -j"$(sysctl -n hw.logicalcpu 2>/dev/null || nproc)"
if [ -x app/Artemis.app/Contents/MacOS/Artemis ]; then
  APP_BIN=app/Artemis.app/Contents/MacOS/Artemis
else
  APP_BIN=app/artemis
fi
"$APP_BIN" --build-info | python3 -m json.tool
cd ../..
```

Expected: tests pass; the local app reports channel `none`, sequence `"0"`, and
does not open a window.

- [ ] **Step 6: Commit**

```bash
git add app/backend/buildinfo.* app/app.pro app/main.cpp tests/autoupdate
git commit -m "feat: embed Vibertemis build identity"
```

### Task 2: Parse rolling releases, tags, manifests, ancestry, and SteamOS sessions

**Files:**
- Create: `app/backend/updateresult.h`
- Create: `app/backend/rollingupdateparser.h`
- Create: `app/backend/rollingupdateparser.cpp`
- Create: `app/backend/steamdecksession.h`
- Create: `app/backend/steamdecksession.cpp`
- Modify: `app/app.pro`
- Modify: `tests/autoupdate/autoupdate.pro`
- Modify: `tests/autoupdate/tst_autoupdate.cpp`

- [ ] **Step 1: Write failing pure-parser tests**

Cover:

- exact release tag `steam-deck-latest`;
- release ID, asset IDs, sizes, timestamps, and API/download URLs;
- update manifest schema `1`;
- exact repository and application ID;
- full lowercase source commit and decimal-string sequence;
- exact Flatpak asset ID/name/size/digest binding;
- duplicate/missing assets, foreign repositories, non-HTTPS URLs, URL userinfo,
  query/fragment surprises, abbreviated commits, integer overflow, unknown
  schemas, and manifest bodies above the cap;
- recursive annotated-tag dereferencing, commit completion, invalid object
  types, and tag cycles;
- GitHub compare statuses `ahead`, `identical`, `behind`, `diverged`, and
  unknown, where only `ahead` is installable; and
- candidate sequence must be greater than the running rolling sequence.

Use this valid manifest fixture:

```json
{
  "schema": 1,
  "repository": "samelamin/vibertemis",
  "application_id": "com.artemisdesktop.ArtemisDesktopDev",
  "source_commit": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "build_sequence": "5678",
  "release_id": "24680",
  "tag": "steam-deck-latest",
  "tag_commit": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "flatpak": {
    "asset_id": "13579",
    "name": "artemis-steam-deck.flatpak",
    "size": "1048576",
    "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
  },
  "published_at": "2026-07-23T10:00:00Z"
}
```

- [ ] **Step 2: Write failing SteamOS session tests**

Pass an explicit `QProcessEnvironment` to the detector and assert:

```text
GAMESCOPE_WAYLAND_DISPLAY=gamescope-0                       -> Gaming
XDG_CURRENT_DESKTOP=gamescope                               -> Gaming
XDG_CURRENT_DESKTOP=KDE; KDE_FULL_SESSION=true;
XDG_SESSION_TYPE=wayland                                    -> Desktop
KDE values plus a Gamescope signal                          -> Gaming
missing, partial, conflicting, or unrelated values          -> Unknown
```

- [ ] **Step 3: Run the focused tests and verify failure**

Run:

```bash
cd build/tests-autoupdate
qmake6 ../../tests/autoupdate/autoupdate.pro
make -j"$(sysctl -n hw.logicalcpu 2>/dev/null || nproc)"
./tst_autoupdate rollingParser steamDeckSession
```

Expected: compilation fails on the missing parser and detector.

- [ ] **Step 4: Implement pure value types and strict parsing**

Define the shared result first:

```cpp
enum class UpdateError {
    None, InvalidBuildIdentity, InvalidMetadata, UnsafeUrl, HttpFailure,
    RateLimited, Timeout, RedirectRejected, ResponseTooLarge,
    CandidateNotAhead, InsufficientSpace, UnsafePath, IoFailure,
    SizeMismatch, DigestMismatch, PublisherChanged, Cancelled,
    SessionNotDesktop, PortalFailure
};

template<typename T>
struct UpdateResult {
    bool ok = false;
    T value {};
    UpdateError error = UpdateError::None;
    QString message;
};
```

Then define:

```cpp
struct RollingAssetIdentity {
    quint64 id;
    QString name;
    quint64 size;
    QUrl apiUrl;
    QUrl downloadUrl;
    QDateTime updatedAt;
    QByteArray sha256;
};

struct RollingUpdateCandidate {
    quint64 releaseId;
    QString releaseLabel;
    QUrl releasePage;
    QDateTime releaseUpdatedAt;
    QString sourceCommit;
    quint64 sequence;
    QString tagRefObjectId;
    QString tagObjectId;
    int manifestSchema;
    QDateTime publishedAt;
    RollingAssetIdentity manifest;
    RollingAssetIdentity flatpak;
};

enum class CommitRelation { CandidateAhead, Equal, CandidateBehind, Diverged, Unknown };
```

Keep parsing functions side-effect-free and return a typed result containing
either a value or a stable error code plus display-safe message. Decisions use
full hashes only. Restrict endpoints to:

```text
api.github.com/repos/samelamin/vibertemis/...
github.com/samelamin/vibertemis/releases/...
objects.githubusercontent.com
github-releases.githubusercontent.com
release-assets.githubusercontent.com
```

Permit CDN subdomains only when the hostname ends at a dot boundary; never use
substring matching.

For the manifest asset, `sha256` is empty because the release does not contain
a separate trusted digest for its own metadata; its captured ID, name, exact
size, API/download URLs, and `updated_at` are nevertheless refetched and
compared. The Flatpak asset requires all fields including SHA-256. Tests mutate
each release, tag-ref, tag-object, manifest, and Flatpak identity field one at a
time and require candidate invalidation.

- [ ] **Step 5: Implement conservative session classification**

Create:

```cpp
class SteamDeckSession
{
public:
    enum Mode { Desktop, Gaming, Unknown };
    static Mode classify(const QProcessEnvironment &environment);
    static Mode current();
};

class SessionModeProvider
{
public:
    virtual ~SessionModeProvider() = default;
    virtual SteamDeckSession::Mode mode() const = 0;
};

class EnvironmentSessionModeProvider final : public SessionModeProvider
{
public:
    SteamDeckSession::Mode mode() const override;
};
```

Check Gaming signals first, require every Desktop signal, compare
case-insensitively where specified, and return `Unknown` for all other input.

- [ ] **Step 6: Run all updater tests**

Expected: new parser/session tests and existing stable release-selection tests
pass.

- [ ] **Step 7: Commit**

```bash
git add app/backend/updateresult.h app/backend/rollingupdateparser.* app/backend/steamdecksession.* app/app.pro tests/autoupdate
git commit -m "feat: validate Steam Deck update metadata"
```

### Task 3: Secure download verification and cross-session pending records

**Files:**
- Create: `app/backend/pendingupdate.h`
- Create: `app/backend/pendingupdate.cpp`
- Modify: `app/app.pro`
- Modify: `tests/autoupdate/autoupdate.pro`
- Modify: `tests/autoupdate/tst_autoupdate.cpp`

- [ ] **Step 1: Write failing secure-file tests in a temporary root**

Inject Downloads/private-data roots and a fake storage probe. Test:

- free-space rejection with the configured safety margin;
- randomized `.part` creation is exclusive and inside Downloads;
- exact byte-count and SHA-256 success;
- short, oversized, or digest-mismatched files are removed;
- an existing verified final file is reused only after rehashing;
- the final filename is exactly
  `artemis-steam-deck-<first-12-commit-hex>.flatpak`;
- final symlinks and paths escaping Downloads are rejected;
- only stale files matching the app-owned prefix are cleaned.

On Unix add a race fixture that swaps a pathname after open and prove hashing
continues on the already-open inode.

- [ ] **Step 2: Run focused tests and verify failure**

Run:

```bash
./build/tests-autoupdate/tst_autoupdate secureUpdateFiles
```

Expected: compilation fails because the secure file store does not exist.

- [ ] **Step 3: Implement the storage seam and secure file operations**

Expose:

```cpp
class StorageProbe
{
public:
    virtual ~StorageProbe() = default;
    virtual quint64 bytesAvailable(const QString &directory) const = 0;
    virtual QDateTime nowUtc() const = 0;
};

struct PendingUpdateRecord {
    int schema = 1;
    QString canonicalPath;
    RollingUpdateCandidate candidate;
    quint64 verifiedSize = 0;
    QByteArray verifiedSha256;
};

class UpdateFileStore
{
public:
    virtual ~UpdateFileStore() = default;
    struct OpenVerifiedFile {
        QSharedPointer<QFile> file;
        QString canonicalPath;
        QByteArray sha256;
        quint64 size;
    };

    virtual UpdateResult<QSharedPointer<QTemporaryFile>> createDownload(
        quint64 expectedSize) = 0;
    virtual UpdateResult<OpenVerifiedFile> finalizeAndVerify(
        QSharedPointer<QTemporaryFile> temporary,
        const RollingUpdateCandidate &candidate) = 0;
    virtual UpdateResult<OpenVerifiedFile> reopenAndVerify(
        const PendingUpdateRecord &record,
        const RollingUpdateCandidate &currentBinding) = 0;
    virtual bool save(const PendingUpdateRecord &record) = 0;
    virtual UpdateResult<PendingUpdateRecord> load() = 0;
    virtual void clear(bool removeOwnedPayload) = 0;
    virtual void cleanStaleParts() = 0;
};

class PendingUpdateStore final : public UpdateFileStore
{
public:
    explicit PendingUpdateStore(QString downloadsRoot = {},
                                QString privateDataRoot = {},
                                StorageProbe *probe = nullptr);
    // Implements every UpdateFileStore method above.
};
```

The production constructor owns a `QStorageInfo`/UTC-clock probe when `probe`
is null; tests pass a fake with deterministic space and time. Require expected
size plus a fixed safety margin before creating a file.

Use `QTemporaryFile` in Downloads for exclusive random creation and `QSaveFile`
for the private JSON record. On Unix reopen with `open(...,
O_RDONLY|O_CLOEXEC|O_NOFOLLOW)` and wrap that descriptor in `QFile`; validate
`fstat()` regular-file identity. Validate canonical containment with a path
separator boundary, not string prefix alone. Keep the verified `QFile` alive
until portal response or explicit cancellation.

- [ ] **Step 4: Run the secure-file slice and verify it passes**

Run:

```bash
./build/tests-autoupdate/tst_autoupdate secureUpdateFiles
```

Expected: secure path, size, digest, short-name, race, and free-space tests pass.

- [ ] **Step 5: Write failing pending-record tests**

Require atomic storage of the full `PendingUpdateRecord`, including every
release, tag-ref, tag-object, manifest, Flatpak, sequence, path, size, digest,
and timestamp field. Test valid restart; malformed/tampered record;
missing/replaced file; symlink; and changed release binding. Unrelated files
are never opened or deleted. The service, not the store, decides whether the
running commit already equals the candidate.

- [ ] **Step 6: Run the record slice and verify failure**

Run:

```bash
./build/tests-autoupdate/tst_autoupdate pendingUpdateRecord
```

Expected: failures from the not-yet-implemented save/load/reopen methods.

- [ ] **Step 7: Implement atomic record persistence and restoration**

Serialize numeric 64-bit values as decimal strings, reject unknown/missing
fields, and validate the loaded record as untrusted input. A stale or invalid
record is cleared only after containment/no-follow validation; unrelated
targets are preserved.

- [ ] **Step 8: Run filesystem tests under sanitizers where available**

Run the full updater suite normally. On Linux CI also compile the suite with
ASan/UBSan if the existing toolchain permits it.

Expected: no leak, double-close, symlink-follow, or cleanup-scope failures.

- [ ] **Step 9: Commit**

```bash
git add app/backend/pendingupdate.* app/app.pro tests/autoupdate
git commit -m "feat: secure Steam Deck update files"
```

### Task 4: Turn `AutoUpdateChecker` into a persistent, bounded state service

**Files:**
- Create: `app/backend/updatestatemachine.h`
- Create: `app/backend/updatestatemachine.cpp`
- Modify: `app/backend/autoupdatechecker.h`
- Modify: `app/backend/autoupdatechecker.cpp`
- Modify: `tests/autoupdate/autoupdate.pro`
- Modify: `tests/autoupdate/tst_autoupdate.cpp`

- [ ] **Step 1: Write failing state-transition tests**

Expose these QML-readable values:

```cpp
enum State {
    Idle, RestoringPending, Checking, NoUpdate, Available, Downloading,
    Verifying, ReadyForDesktop, ReadyToHandOff, CheckError, DownloadError,
    VerificationError, RestoreError, Cancelled
};
Q_ENUM(State)

Q_PROPERTY(State state READ state NOTIFY stateChanged)
Q_PROPERTY(QString currentBuild READ currentBuild CONSTANT)
Q_PROPERTY(QString availableBuild READ availableBuild NOTIFY candidateChanged)
Q_PROPERTY(QString releaseUrl READ releaseUrl NOTIFY candidateChanged)
Q_PROPERTY(QString downloadedPath READ downloadedPath NOTIFY downloadedPathChanged)
Q_PROPERTY(QString manualInstallCommand READ manualInstallCommand NOTIFY downloadedPathChanged)
Q_PROPERTY(qint64 bytesReceived READ bytesReceived NOTIFY progressChanged)
Q_PROPERTY(qint64 bytesTotal READ bytesTotal NOTIFY progressChanged)
Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY errorChanged)
Q_PROPERTY(bool rollingInstallSupported READ rollingInstallSupported CONSTANT)
```

Test every allowed non-portal transition from the spec through a pure event
reducer, and explicitly reject:

- concurrent checks/downloads;
- download before an `Available` candidate;
- resuming a stale candidate without release/tag/manifest refetch.

In this first slice, test only restoration decisions that need no network:
absent record starts a normal check, malformed record is invalidated, and a
record whose candidate equals the running full commit is cleared and reaches
`NoUpdate`. Preserve the existing `onUpdateAvailable(version, url)` stable
behavior.

- [ ] **Step 2: Run the state slice and verify failure**

Run:

```bash
./build/tests-autoupdate/tst_autoupdate stateMachine
```

Expected: failures because the reducer and persistent checker states do not
exist.

- [ ] **Step 3: Implement the pure reducer and dependency seams**

Define:

```cpp
class UpdateStateMachine
{
public:
    enum Event {
        BeginRestore, BeginCheck, CandidateAvailable, CandidateCurrent,
        BeginDownload, DownloadComplete, VerificationPassedDesktop,
        VerificationPassedNonDesktop, CheckFailed, DownloadFailed,
        VerificationFailed, RestoreFailed, Retry, Cancel
    };
    static UpdateResult<AutoUpdateChecker::State> reduce(
        AutoUpdateChecker::State current, Event event);
};
```

Keep the authoritative `State` enum in `AutoUpdateChecker` with `Q_ENUM`.
`updatestatemachine.h` includes `autoupdatechecker.h`; the checker header does
not include the reducer, and `autoupdatechecker.cpp` includes both, avoiding a
cycle. Portal events are added and tested in Task 5.

Provide both constructors:

```cpp
explicit AutoUpdateChecker(QObject *parent = nullptr);
AutoUpdateChecker(QNetworkAccessManager *network,
                  UpdateFileStore *files,
                  SessionModeProvider *session,
                  QObject *parent = nullptr);
```

The default constructor creates and owns production dependencies as QObject
children or private owned values. The injected constructor borrows test fakes
for the checker's lifetime. A test `FakeNetworkAccessManager` subclasses
`QNetworkAccessManager::createRequest()` and returns scripted
`QNetworkReply` objects; fake file/session implementations satisfy the
interfaces defined in Tasks 2–3.

Implement state guards, startup restoration routing, cancel/discard, typed
retry origin, QML properties, and the existing stable signal. For an existing
pending record, compare the running full commit to the recorded candidate
before asking the store to reopen it; equality clears the record and reduces
`RestoringPending` to `NoUpdate`. Do not add portal states or
`openInstaller()` yet.

- [ ] **Step 4: Run the state slice and verify it passes**

Expected: all state and restoration transition tests pass.

- [ ] **Step 5: Write failing bounded-network tests**

Use fake `QNetworkReply` objects and an injectable network manager/factory.
Require:

- 2xx only;
- maximum five redirects;
- approved-host validation on every hop;
- no authorization/cookie propagation across origins;
- 1 MiB caps for release/tag/compare JSON and 64 KiB for update manifest;
- exact Flatpak byte count from the manifest;
- connect/idle/overall timeouts;
- cancellation aborts the reply and removes the partial;
- API rate-limit message uses headers without exposing tokens;
- release/tag/manifest identities are refetched after hashing; and
- a one-field-at-a-time change to release ID/`updated_at`, tag-ref object, tag
  object, manifest ID/name/size/`updated_at`/schema/publication timestamp,
  source commit, sequence, or Flatpak ID/name/size/`updated_at`/digest enters a
  retryable publisher-in-progress error.

Add end-to-end startup restoration fixtures here, after the transport and file
seams exist: valid pending download, stale release binding, replaced local
file, missing file after record creation, and a publication that changes during
the Desktop-mode refetch. Require only the valid unchanged fixture to reach
`ReadyToHandOff`.

- [ ] **Step 6: Run the bounded-network slice and verify failure**

Run:

```bash
./build/tests-autoupdate/tst_autoupdate boundedNetwork
```

Expected: failures because network orchestration is not implemented.

- [ ] **Step 7: Implement platform routing and persistent networking**

Keep one `QNetworkAccessManager` for the service lifetime. `start()` performs a
quiet background check; add:

```cpp
Q_INVOKABLE void checkNow();
Q_INVOKABLE void downloadUpdate();
Q_INVOKABLE void cancel();
Q_INVOKABLE void retry();
Q_INVOKABLE void discardPendingUpdate();
Q_INVOKABLE void openReleasePage();
```

Routing rules:

- internally consistent rolling Flatpak build: rolling release flow;
- supported Windows/macOS/Steam Link/AppImage package: existing semantic flow;
- all other builds: never offer rolling install.

For compare, request
`/repos/samelamin/vibertemis/compare/<current>...<candidate>` and accept only
GitHub status `ahead` plus a higher manifest sequence. Implement redirect and
body caps before buffering. Use request-scoped timers for connect, idle, and
overall limits. Never destroy the manager after the first check.

- [ ] **Step 8: Implement download, verification, and restoration orchestration**

Before download, check the Downloads directory and free space. Stream bytes to
the secure temporary file while hashing and enforcing exact maximum size.
After local verification, refetch the release, recursively dereference the tag,
and fetch the current manifest. Save the pending record only when every binding
matches. Select `ReadyToHandOff` only for positive Desktop mode; otherwise use
`ReadyForDesktop`.

At startup, a pending record enters `RestoringPending`, validates/refetches,
reopens without following links, rehashes the same descriptor, and then enters
the appropriate ready state. Type failures by origin so retry returns to the
correct state.

- [ ] **Step 9: Run the bounded-network, restoration, and entire updater suites**

Expected: stable and rolling tests pass with no live network dependency.

- [ ] **Step 10: Commit**

```bash
git add app/backend/updatestatemachine.* app/backend/autoupdatechecker.* tests/autoupdate
git commit -m "feat: orchestrate verified Steam Deck updates"
```

### Task 5: Add Linux desktop-portal installer handoff

**Files:**
- Create: `app/backend/desktopinstallerportal.h`
- Create: `app/backend/desktopinstallerportal.cpp`
- Modify: `app/backend/updatestatemachine.h`
- Modify: `app/backend/updatestatemachine.cpp`
- Modify: `app/backend/autoupdatechecker.h`
- Modify: `app/backend/autoupdatechecker.cpp`
- Modify: `app/app.pro`
- Modify: `tests/autoupdate/autoupdate.pro`
- Modify: `tests/autoupdate/tst_autoupdate.cpp`

- [ ] **Step 1: Write failing portal contract tests**

Extend the injected checker constructor with an `InstallerPortal *` fake and
require that:

- only `ReadyToHandOff` in positive Desktop mode calls it;
- the argument is the still-open read-only verified descriptor;
- the D-Bus target is `org.freedesktop.portal.Desktop`;
- object/interface/method are `/org/freedesktop/portal/desktop`,
  `org.freedesktop.portal.OpenURI`, and `OpenFile`;
- options include `writable=false` and `ask=true`;
- a method failure enters `HandOffError`;
- a portal response other than success enters `HandOffError`;
- portal response success enters `HandOffRequested`, not an installed/success
  state; and
- retry revalidates the file before a second handoff;
- Gaming/unknown mode never calls the portal; and
- portal acceptance retains the pending record until the candidate build is
  observed on a later launch or the user explicitly discards it.

Extend the pure reducer tests with every portal event and legal/illegal
transition: `ReadyToHandOff -> HandingOff -> HandOffRequested|HandOffError`,
and `HandOffError -> Verifying` before any retry can return to
`ReadyToHandOff`.

- [ ] **Step 2: Run the portal tests and verify failure**

Expected: failure because no portal adapter exists.

- [ ] **Step 3: Implement the Linux-only adapter**

Declare a small interface:

```cpp
class InstallerPortal : public QObject
{
    Q_OBJECT
public:
    virtual void openFlatpak(const QSharedPointer<QFile> &verifiedFile) = 0;
signals:
    void response(bool accepted, const QString &message);
};
```

Add `HandingOff`, `HandOffRequested`, and `HandOffError` to the checker state
enum; add `Q_INVOKABLE void openInstaller()`; and add the four-argument
dependency constructor:

```cpp
AutoUpdateChecker(QNetworkAccessManager *network,
                  UpdateFileStore *files,
                  SessionModeProvider *session,
                  InstallerPortal *portal,
                  QObject *parent = nullptr);
```

On Linux, wrap `verifiedFile->handle()` in `QDBusUnixFileDescriptor`, invoke
`OpenFile` asynchronously, validate the returned request object path, and
listen for its `org.freedesktop.portal.Request.Response` signal. Do not use a
`file://` URL, `flatpak-spawn`, a shell, or
`org.freedesktop.Flatpak`. Compile Qt D-Bus only for `unix:!macx`; other
platforms receive a no-op adapter and keep their existing release-page action.

- [ ] **Step 4: Run C++ tests and Linux compile-sanity**

Expected: mocked portal tests pass; Linux links Qt D-Bus; macOS and Windows
project generation do not require Qt D-Bus.

- [ ] **Step 5: Commit**

```bash
git add app/backend/desktopinstallerportal.* app/backend/updatestatemachine.* app/backend/autoupdatechecker.* app/app.pro tests/autoupdate
git commit -m "feat: hand verified Flatpaks to the desktop portal"
```

### Task 6: Build the controller-first update dialog and manual check entry

**Files:**
- Create: `app/gui/UpdateDialog.qml`
- Modify: `app/gui/main.qml`
- Modify: `app/gui/SettingsView.qml`
- Modify: `app/qml.qrc`
- Create: `packaging/flatpak/tests/test_updater_packaging.py`

- [ ] **Step 1: Write failing QML contract tests**

The Python contract test must require:

- `UpdateDialog.qml` is in `app/qml.qrc`;
- `main.qml` opens the dialog only after the user activates the update button;
- no background check opens a modal;
- the toolbar update button remains the existing native release-page behavior
  outside rolling Flatpak builds;
- Settings contains a controller-focusable **Check for updates** button;
- the dialog has explicit Download, Cancel, Retry, Later, View release, Open
  installer, and Copy manual command actions;
- Open installer is visible/enabled only for `ReadyToHandOff`;
- `ReadyForDesktop` text tells users to switch modes and preserves the file;
- handoff copy says “installer requested,” never “installed” or “updated
  successfully”;
- initial focus, left/right focus movement, Return/Enter/A activation,
  Escape/B dismissal, cancel behavior, and focus restoration are explicit; and
- the manual command is exactly:

```text
flatpak install --user --or-update "<verified Downloads path>"
```

The backend constructs this display-only fallback from `downloadedPath` and
POSIX double-quote escapes backslashes, double quotes, dollar signs, and
backticks. QML must only display and copy the backend property in verified
states; it must not concatenate the path or execute the command.

- [ ] **Step 2: Run the contract test and verify failure**

Run:

```bash
python3 -m unittest vibertemis_packaging_tests.test_updater_packaging -v
```

Expected: failure because the dialog and settings entry do not exist.

- [ ] **Step 3: Implement the dialog as a state projection**

Use `NavigableDialog` and existing Material controls. Bind labels, progress,
buttons, and error text directly to `AutoUpdateChecker.state`; keep networking
and policy out of QML. Give every visible state one primary action and one safe
way back. Use determinate progress only when `bytesTotal > 0`.

The toolbar button:

- opens `UpdateDialog` for rolling Flatpak candidates;
- opens the existing release URL for stable/native candidates; and
- remains hidden when no update is available.

The Settings button calls `checkNow()`, opens the dialog for user-initiated
checking, and exposes `NoUpdate`/error feedback that quiet startup checks do not
surface.

- [ ] **Step 4: Run QML contracts and an offscreen startup**

Run:

```bash
python3 -m unittest vibertemis_packaging_tests.test_updater_packaging -v
mkdir -p build/native-qml
cd build/native-qml
qmake6 ../../artemis.pro CONFIG+=release
make -j"$(sysctl -n hw.logicalcpu 2>/dev/null || nproc)"
if [ -x app/Artemis.app/Contents/MacOS/Artemis ]; then
  APP_BIN=app/Artemis.app/Contents/MacOS/Artemis
else
  APP_BIN=app/artemis
fi
set +e
QT_QPA_PLATFORM=offscreen SDL_VIDEODRIVER=dummy \
  perl -e 'alarm shift; exec @ARGV' 10 "$APP_BIN" >qml-smoke.log 2>&1
QML_SMOKE_STATUS=$?
set -e
if [ "$QML_SMOKE_STATUS" -ne 142 ]; then
  cat qml-smoke.log
  exit 1
fi
if grep -Eiq 'failed to load component|module .* is not installed|is not a type|QQmlApplicationEngine failed' qml-smoke.log; then
  cat qml-smoke.log
  exit 1
fi
cd ../..
```

Expected: contracts pass; status 142 proves the bounded alarm stopped a
still-running app, and captured output has no QML type/import errors.

- [ ] **Step 5: Commit**

```bash
git add app/gui/UpdateDialog.qml app/gui/main.qml app/gui/SettingsView.qml app/qml.qrc packaging/flatpak/tests/test_updater_packaging.py
git commit -m "feat: add the Steam Deck update dialog"
```

### Task 7: Add Flatpak permission and CI package identities

**Files:**
- Modify: `packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.json`
- Create: `packaging/flatpak/prepare-ci-manifest.py`
- Modify: `packaging/flatpak/tests/test_updater_packaging.py`
- Modify: `packaging/flatpak/tests/test_validate_manifest.py`
- Modify: `.github/workflows/dev-build.yml`

- [ ] **Step 1: Write failing manifest/identity tests**

Require:

- the sole new filesystem permission is `--filesystem=xdg-download`;
- no `--filesystem=home`, host write permission, `flatpak-spawn`, or
  `org.freedesktop.Flatpak` talk permission;
- the helper accepts only a lowercase full SHA, channel
  `rolling|stable|none`, decimal sequence, and exact application ID;
- rolling requires a positive sequence; stable/none require zero;
- output remains in `packaging/flatpak/` so relative source paths are stable;
- the tracked manifest remains unchanged by helper execution;
- fork `main` builds receive rolling metadata from `github.sha` and
  `github.run_id` only in the Steam Deck Flatpak;
- native Windows, macOS, and AppImage packages produced for the fork's
  `main` release receive full `github.sha`, channel `stable`, and sequence zero;
- pull requests, forks, and non-main branches receive channel `none` and
  sequence zero; and
- CI runs `/app/bin/artemis --build-info`, parses it, and checks every field
  before bundling/upload;
- the Linux/macOS `compile-sanity` matrix builds and executes
  `tests/autoupdate` with `QT_QPA_PLATFORM=offscreen`.

- [ ] **Step 2: Run packaging tests and verify failure**

Expected: failures for missing permission, helper, and workflow identity.

- [ ] **Step 3: Implement deterministic manifest preparation**

The helper accepts:

```text
--input packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.json
--output packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.ci.json
--commit <40-hex>
--channel <rolling|stable|none>
--sequence <decimal>
--application-id com.artemisdesktop.ArtemisDesktopDev
```

It loads JSON, finds exactly one `artemis` qmake module, and appends these qmake
config options without editing the tracked file:

```text
VIBERTEMIS_BUILD_COMMIT=<full sha>
VIBERTEMIS_UPDATE_CHANNEL=<channel>
VIBERTEMIS_BUILD_SEQUENCE=<sequence>
VIBERTEMIS_APPLICATION_ID=com.artemisdesktop.ArtemisDesktopDev
```

Reject duplicate identity options and unsafe characters.

- [ ] **Step 4: Mark native CI packages without marking local builds**

At workflow scope, derive environment values that qmake reads through the
fallbacks added in Task 1:

```yaml
VIBERTEMIS_BUILD_COMMIT: ${{ github.sha }}
VIBERTEMIS_UPDATE_CHANNEL: >-
  ${{ github.repository == 'samelamin/vibertemis' &&
      github.ref == 'refs/heads/main' && 'stable' || 'none' }}
VIBERTEMIS_BUILD_SEQUENCE: 0
```

Set the native application ID explicitly to
`com.artemis_desktop.Artemis`. This marks only fork `main` native packages as
stable; developer, pull-request, fork, and local builds remain `none`. The
Flatpak's explicit qmake config options override these environment defaults.
Add build-info inspection to each published Windows/macOS/AppImage artifact
before upload, using:

```text
build/build-x64-release/app/release/Artemis.exe
build/build-arm64-release/app/release/Artemis.exe
build/build-Release/app/Vibertemis.app/Contents/MacOS/Artemis
app/artemis
```

If an existing packaging script moves the binary, inspect it immediately
before that move at the verified path already used by that script. Parse JSON
and assert full commit, stable/none policy, sequence zero, application ID, and
version before packaging continues.

- [ ] **Step 5: Update the Flatpak build job**

Derive channel and sequence in a shell step using exact repository/ref checks,
write them to step outputs, generate the CI manifest, build from it, then run:

```bash
flatpak build flatpak-build /app/bin/artemis --build-info > flatpak-build-info.json
python3 -c 'import json,sys; data=json.load(open("flatpak-build-info.json")); ...'
```

The early `QCoreApplication` path from Task 1 makes this safe without a display;
also set `QT_QPA_PLATFORM=offscreen` defensively in CI. Assert schema,
application ID, full `github.sha`, expected channel, run ID/zero sequence, and
current version. Upload `flatpak-build-info.json` beside the Flatpak for
publisher inspection.

- [ ] **Step 6: Execute updater tests in both compile-sanity matrix legs**

After the existing application compile in `compile-sanity`, add:

```bash
mkdir -p build/tests-autoupdate
cd build/tests-autoupdate
qmake6 ../../tests/autoupdate/autoupdate.pro
make -j"$(sysctl -n hw.logicalcpu 2>/dev/null || nproc)"
QT_QPA_PLATFORM=offscreen ./tst_autoupdate
```

Keep this step inside the Linux/macOS matrix so both platforms compile and run
the updater suite. The Linux project links Qt D-Bus; the macOS project verifies
the non-D-Bus adapter and existing semantic updater.

- [ ] **Step 7: Run packaging tests and manifest validation**

Run:

```bash
python3 -m unittest discover -s packaging/flatpak/tests -p 'test_*.py' -v
python3 packaging/flatpak/validate-manifest.py packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.json
```

Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
git add packaging/flatpak .github/workflows/dev-build.yml
git commit -m "build: mark verified rolling Flatpak builds"
```

### Task 8: Publish a race-detectable rolling update manifest from `main`

**Files:**
- Create: `packaging/flatpak/generate-update-manifest.py`
- Modify: `packaging/flatpak/tests/test_updater_packaging.py`
- Modify: `packaging/flatpak/tests/test_fork_identity.py`
- Modify: `.github/workflows/dev-build.yml`

- [ ] **Step 1: Write failing publisher protocol tests**

Require the `publish-steam-deck-release` job to:

- run only for `samelamin/vibertemis` `refs/heads/main`;
- serialize with the existing non-cancelling concurrency group;
- confirm current `main` head before artifact handling and again before moving
  the tag;
- inspect the artifact's uploaded `flatpak-build-info.json` before mutation;
- replace the Flatpak, query its exact API asset ID, size, and `updated_at`;
- download that exact asset by API ID with `Accept:
  application/octet-stream`, then verify byte size and SHA-256;
- generate `artemis-steam-deck-update.json` with string-encoded 64-bit IDs and
  sizes;
- upload manifest, checksum, and archive;
- refetch every public asset by ID and verify the manifest binding;
- recursively dereference the public rolling tag and reject unexpected tag
  object types/cycles;
- move `steam-deck-latest` only after all assets verify;
- verify the final tag commit equals source and manifest; and
- update release notes last.

Keep tests proving only known, confirmed existing assets are deleted. Add the
new manifest to the established asset contract.

- [ ] **Step 2: Run publisher tests and verify failure**

Run:

```bash
python3 -m unittest \
  vibertemis_packaging_tests.test_updater_packaging \
  vibertemis_packaging_tests.test_fork_identity -v
```

Expected: failures because the job is branch-gated to
`codex/steam-deck`, moves the tag too early, and has no update manifest.

- [ ] **Step 3: Implement deterministic manifest generation**

`generate-update-manifest.py` validates CLI inputs and writes sorted compact
JSON plus newline. Its output is exactly:

```json
{
  "application_id": "com.artemisdesktop.ArtemisDesktopDev",
  "build_sequence": "<github.run_id>",
  "flatpak": {
    "asset_id": "<asset database id>",
    "name": "artemis-steam-deck.flatpak",
    "sha256": "<64 lowercase hex>",
    "size": "<bytes>"
  },
  "published_at": "<UTC RFC3339>",
  "release_id": "<release database id>",
  "repository": "samelamin/vibertemis",
  "schema": 1,
  "source_commit": "<github.sha>",
  "tag": "steam-deck-latest",
  "tag_commit": "<github.sha>"
}
```

Treat GitHub numeric identifiers as decimal strings to avoid QJson/JavaScript
precision loss.

- [ ] **Step 4: Reorder the publisher**

Preserve the existing prerelease for stable URLs. Mark notes as updating,
remove only the four known assets, upload Flatpak, query/re-download/verify its
exact asset ID, generate/upload the update manifest, upload checksum/archive,
and verify all four. Recheck `main` head, then force-move the tag to
`SOURCE_COMMIT`. Recursively dereference and compare the final commit. The
release/tag/manifest mismatch window is intentionally non-installable because
the app captures and refetches every identity.

For a first-ever release, create the prerelease/tag before upload; because no
previous updater can observe it, this bootstrap exception does not expose a
stale trusted state. Every replacement follows tag-last ordering.

- [ ] **Step 5: Parse/lint and run all packaging tests**

Run:

```bash
python3 -m unittest discover -s packaging/flatpak/tests -p 'test_*.py' -v
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/dev-build.yml", aliases: true)'
actionlint .github/workflows/dev-build.yml
git diff --check
```

Expected: Python/YAML checks pass and actionlint has no new findings relative
to the recorded baseline.

- [ ] **Step 6: Commit**

```bash
git add packaging/flatpak/generate-update-manifest.py packaging/flatpak/tests .github/workflows/dev-build.yml
git commit -m "ci: bind Steam Deck updates to exact assets"
```

### Task 9: Publish the dummy-proof Steam Deck guide

**Files:**
- Create: `docs/STEAM_DECK_QUICK_START.md`
- Modify: `README.md`
- Modify: `docs/STEAM_DECK.md`
- Modify: `packaging/flatpak/tests/test_flatpak_documentation.py`
- Modify: `packaging/flatpak/tests/test_updater_packaging.py`

- [ ] **Step 1: Write failing documentation contracts**

Require:

- README has a prominent “Installing on Steam Deck? Start here” link before the
  advanced material;
- quick start and advanced guides link to each other;
- the first-install block downloads the atomic archive into a new temporary
  directory, extracts it there, runs `sha256sum -c`, and only then runs
  `flatpak install --user --or-update`;
- a shell fixture with a deliberately corrupted Flatpak exits before a fake
  `flatpak` executable is called;
- commands use the exact maintained URLs and
  `com.artemisdesktop.ArtemisDesktopDev`;
- no command uses `sudo`, SteamOS unlocks, `flatpak-spawn`, or general host
  access;
- the guide explicitly says not to disable SteamOS read-only protection and
  not to paste commands from issue comments without reviewing them;
- Desktop Mode, adding to Steam, Gaming Mode, update, launch, uninstall,
  diagnostics, and manual fallback each have copy/paste commands; and
- documentation says “ready for Steam Deck testing,” not hardware-tested.

- [ ] **Step 2: Run documentation tests and verify failure**

Expected: missing quick-start failures and direct-unverified README install
failure.

- [ ] **Step 3: Write the quick start**

Use one self-contained first-install block based on:

```bash
set -euo pipefail
VIBERTEMIS_INSTALL_DIR="$(mktemp -d "$HOME/Downloads/vibertemis-install.XXXXXX")"
cd "$VIBERTEMIS_INSTALL_DIR"
curl --fail --location --retry 3 \
  --output artemis-steam-deck-bundle.tar.gz \
  https://github.com/samelamin/vibertemis/releases/download/steam-deck-latest/artemis-steam-deck-bundle.tar.gz
tar --extract --gzip --file artemis-steam-deck-bundle.tar.gz
sha256sum --check artemis-steam-deck.flatpak.sha256
flatpak remote-add --user --if-not-exists flathub \
  https://flathub.org/repo/flathub.flatpakrepo
flatpak install --user --or-update artemis-steam-deck.flatpak
flatpak info --user com.artemisdesktop.ArtemisDesktopDev
```

Explain each result in plain language. Do not hide cleanup inside the primary
block; keep the verified file available for diagnosis. Add exact steps to
launch in Desktop Mode, add a non-Steam game using the application launcher,
return to Gaming Mode, use the in-app updater, recover when Discover does not
open, run the manual quoted install command, collect safe diagnostics, and
uninstall. Put a visible safety note before the first command: never use
`sudo`, never disable SteamOS read-only protection for Vibertemis, and review
commands instead of blindly pasting snippets from issue comments.

- [ ] **Step 4: Simplify README and preserve advanced tuning**

Replace the long direct-download section with the quick-start callout and a
short explanation of the compatibility ID/filenames and verified in-app
update. Keep `docs/STEAM_DECK.md` for Moonlight/Apollo streaming, codecs,
refresh rates, controls, logs, and hardware acceptance; link it back to the
beginner guide.

- [ ] **Step 5: Run documentation and URL audits**

Expected: all copy/paste, application-ID, fork-identity, claim, and corrupted
archive tests pass.

- [ ] **Step 6: Commit**

```bash
git add README.md docs/STEAM_DECK_QUICK_START.md docs/STEAM_DECK.md packaging/flatpak/tests
git commit -m "docs: add the Steam Deck quick start"
```

### Task 10: Full verification, review, push, CI, and release audit

**Files:**
- Modify only files required by verified review or CI findings.

- [ ] **Step 1: Run deterministic local tests**

Run:

```bash
python3 -m unittest discover -s packaging/flatpak/tests -p 'test_*.py' -v
python3 packaging/flatpak/validate-manifest.py packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.json
mkdir -p build/tests-autoupdate
cd build/tests-autoupdate
qmake6 ../../tests/autoupdate/autoupdate.pro
make -j"$(sysctl -n hw.logicalcpu 2>/dev/null || nproc)"
./tst_autoupdate
cd ../..
mkdir -p build/tests-refreshrate
cd build/tests-refreshrate
qmake6 ../../tests/refreshrate/refreshrate.pro
make -j"$(sysctl -n hw.logicalcpu 2>/dev/null || nproc)"
./tst_refreshrate
cd ../..
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/dev-build.yml", aliases: true)'
git diff --check
```

Expected: every command succeeds. Run actionlint when installed and compare
against the existing baseline.

- [ ] **Step 2: Build and smoke-test the native macOS app**

Use the repository's current Qt/macOS toolchain:

```bash
VIBERTEMIS_REPO_ROOT="$PWD"
mkdir -p "$VIBERTEMIS_REPO_ROOT/build"
VIBERTEMIS_NATIVE_BUILD="$(mktemp -d "$VIBERTEMIS_REPO_ROOT/build/native-macos.XXXXXX")"
cd "$VIBERTEMIS_NATIVE_BUILD"
qmake6 "$VIBERTEMIS_REPO_ROOT/artemis.pro" CONFIG+=release
make -j"$(sysctl -n hw.logicalcpu)"
"$VIBERTEMIS_NATIVE_BUILD/app/Artemis.app/Contents/MacOS/Artemis" \
  --build-info | python3 -m json.tool
set +e
QT_QPA_PLATFORM=offscreen SDL_VIDEODRIVER=dummy \
  perl -e 'alarm shift; exec @ARGV' 10 \
  "$VIBERTEMIS_NATIVE_BUILD/app/Artemis.app/Contents/MacOS/Artemis" \
  >macos-smoke.log 2>&1
MACOS_SMOKE_STATUS=$?
set -e
if [ "$MACOS_SMOKE_STATUS" -ne 142 ]; then
  cat macos-smoke.log
  exit 1
fi
if grep -Eiq 'failed to load component|module .* is not installed|is not a type|QQmlApplicationEngine failed' macos-smoke.log; then
  cat macos-smoke.log
  exit 1
fi
cd "$VIBERTEMIS_REPO_ROOT"
```

Expected: native build succeeds, local build channel is `none`, build-info is
valid JSON, status 142 is the expected bounded alarm, and captured smoke output
contains no QML import/type failure.

- [ ] **Step 3: Request independent code review**

Use `superpowers:requesting-code-review`. Review:

- updater state and retry correctness;
- GitHub URL/redirect/body/timeout bounds;
- commit ancestry and sequence decisions;
- file descriptor, symlink, containment, persistence, and cleanup safety;
- Desktop/Gaming/unknown mode policy;
- portal request semantics;
- QML controller navigation and truthful copy;
- Flatpak permissions and build identity;
- publisher race windows/tag-last ordering; and
- first-install checksum enforcement.

Fix every confirmed issue and rerun the affected tests.

- [ ] **Step 4: Run verification-before-completion**

Use `superpowers:verification-before-completion`, inspect `git status`, verify
the complete diff is in scope, and rerun the full local suite from a clean
build where feasible.

- [ ] **Step 5: Push `main` and monitor GitHub Actions**

Push the completed commits directly to `origin/main` as the user requested.
Use GitHub/`gh` to follow the resulting workflow to terminal status. If any
enabled job fails, inspect its logs, implement a scoped fix with a regression
test, repush, and continue until the relevant matrix is green.

- [ ] **Step 6: Audit the public rolling release**

After the publisher succeeds:

- fetch release/tag/asset metadata through the GitHub API;
- recursively resolve `steam-deck-latest` to the pushed source commit;
- download the manifest and exact Flatpak asset ID;
- verify manifest release ID, asset ID, size, digest, source commit, and
  sequence;
- verify the sidecar and atomic archive;
- confirm the quick-start URL downloads the same archive; and
- record the workflow run and release URLs.

- [ ] **Step 7: Hand off real-device acceptance**

Report the build commit, CI run, release assets, exact install command, and the
remaining physical checklist for the user's LCD/OLED Steam Deck:

1. clean Desktop install;
2. Desktop and Gaming controller launch;
3. Gaming download/verification;
4. Desktop switch and fresh verification;
5. Discover confirmation and relaunch identity; and
6. manual-command recovery.

Before describing the updater as hardware-tested, capture and record redacted
`XDG_CURRENT_DESKTOP`, `KDE_FULL_SESSION`, `XDG_SESSION_TYPE`, and
`GAMESCOPE_WAYLAND_DISPLAY` snapshots from both Desktop and Gaming Mode.
Update the checked-in session fixtures if SteamOS differs from the assumed
signals, then rerun the detector tests. Never publish usernames, paths, tokens,
or unrelated environment values.

Do not market unrun hardware cases as passed.
