# Artemis Qt Development Plan

This document tracks our progress on implementing Artemis features in the Qt client.

## 🎯 Project Overview

**Goal**: Port Artemis Android features to Moonlight Qt for enhanced Steam Deck and desktop streaming experience.

**Repository**: https://github.com/samelamin/vibertemis
**Base**: Fork of Moonlight Qt  
**Target Platforms**: Windows, macOS, Linux, Steam Deck

## macOS development builds

### Supported toolchain

Use Qt 6.11.1 when building Vibertemis with the current macOS 26 SDKs. Qt 6.8.3
fails in `qyieldcpu.h` because Apple Clang can report support for `__yield`
without declaring `__yield()`. This is
[QTBUG-145239](https://bugreports.qt.io/browse/QTBUG-145239), fixed upstream by
[Qt commit `a76004f16fdc43e1b7af83bfdf3f1a613491b234`](https://github.com/qt/qtbase/commit/a76004f16fdc43e1b7af83bfdf3f1a613491b234),
which prefers `__builtin_arm_yield`. Qt 6.11.1 contains the fix and supports
macOS 26. Do not patch installed Qt headers or suppress the compiler error.

This creates a deliberate, temporary toolchain skew: macOS CI uses Qt 6.11.1,
while the other platform jobs remain on Qt 6.8.3. Application code must not use
Qt 6.11-only APIs. The versions can converge after the common Qt line includes
the upstream fix.

The package script builds the machine's native architecture by default. CI
therefore publishes an artifact such as `vibertemis-macos-arm64-VERSION`; it uses
`universal` only when `lipo -archs` finds both `arm64` and `x86_64`. Set
`ARTEMIS_MAC_ARCHS` explicitly only when the complete Qt and native dependency
set provides every requested slice.

### Reproducible local build

Run these commands from the repository root. They install Qt in a temporary,
isolated directory rather than changing a system Qt installation.

```bash
git submodule update --init --recursive

brew install ffmpeg opus sdl2 sdl2_ttf create-dmg

artemis_qt_root=$(mktemp -d /tmp/artemis-qt-6.11.1.XXXXXX)
python3 -m venv "$artemis_qt_root/venv"
"$artemis_qt_root/venv/bin/python" -m pip install --upgrade pip aqtinstall
"$artemis_qt_root/venv/bin/python" -m aqt install-qt \
  mac desktop 6.11.1 clang_64 -m qtmultimedia -O "$artemis_qt_root/Qt"
export PATH="$artemis_qt_root/Qt/6.11.1/macos/bin:$PATH"

qmake6 -v
sw_vers
xcrun --show-sdk-version
clang --version
uname -m
xcode-select -p
printf '#include <type_traits>\nint main() { return 0; }\n' | \
  clang++ -x c++ -std=c++17 -fsyntax-only -
```

The final preflight must succeed before continuing. The locally tested macOS
26.5.2 Apple Silicon machine selected `/Library/Developer/CommandLineTools`
rather than a full Xcode app, and that local installation was damaged: its
Command Line Tools libc++ overlay was missing `type_traits`. An isolated
diagnostic build was completed only after temporarily pointing
`CPLUS_INCLUDE_PATH` at the SDK's libc++ headers. That environment override is
not a supported project build instruction and must not be added to CI or the
repository scripts. Repair or reinstall Command Line Tools, or select a
complete Xcode installation as CI does, before relying on local results. There
is no Vibertemis source-level workaround for an incomplete Apple toolchain.

Build and run the display-independent refresh-rate unit test, then build,
deploy, package, and verify the application:

```bash
rm -rf build/tests-refreshrate
mkdir -p build/tests-refreshrate
(
  cd build/tests-refreshrate
  qmake6 ../../tests/refreshrate/refreshrate.pro
  make -j"$(sysctl -n hw.logicalcpu)"
  ./tst_refreshrate
)

package_version="$(tr -d '\r\n' < app/version.txt)-local.$(git rev-parse --short HEAD)"
ARTEMIS_MAC_ARCHS="$(uname -m)" \
  ./scripts/generate-dmg.sh Release "$package_version"
./scripts/verify-macos-bundle.sh \
  build/build-Release/app/Vibertemis.app \
  "build/installer-Release/Vibertemis-$package_version.dmg"
```

`generate-dmg.sh` performs a clean release build, runs `macdeployqt`, and writes
the DMG under `build/installer-Release/`. `create-dmg` normally adds the styled
Finder layout. If its Finder automation is unavailable, the script falls back
to `hdiutil`; that DMG remains functional but is unstyled.

For compatibility, qmake still uses `TARGET = Artemis` and first creates
`build/build-Release/app/Artemis.app` with the executable
`Contents/MacOS/Artemis`. The package script immediately renames the enclosing
bundle to the public `Vibertemis.app` name before deployment. The internal
executable and application/settings identity remain `Artemis` for
compatibility. The bundle ID, signing behavior, and `ARTEMIS_MAC_ARCHS`
variable also remain unchanged compatibility contracts.

The verifier checks both the deployed bundle and the copy mounted from the
DMG. For each copy it checks the `Vibertemis` display name, compatibility
bundle ID and `Artemis` executable identity, bundle versions, architecture,
linked libraries, and forbidden direct Homebrew links. It then launches the
real Cocoa application (`QT_QPA_PLATFORM=cocoa` and `SDL_VIDEODRIVER=cocoa`):
surviving five seconds or exiting cleanly passes, while an immediate non-zero
exit exposes loader and platform-plugin failures.

Inspect and fingerprint the result independently:

```bash
app=build/build-Release/app/Vibertemis.app
dmg="build/installer-Release/Vibertemis-$package_version.dmg"
lipo -archs "$app/Contents/MacOS/Artemis"
otool -L "$app/Contents/MacOS/Artemis"
/usr/libexec/PlistBuddy -c 'Print:CFBundleShortVersionString' \
  "$app/Contents/Info.plist"
codesign -dv --verbose=4 "$app"
hdiutil verify "$dmg"
shasum -a 256 "$dmg"
```

### Versions, signing, and verification claims

The project keeps related but distinct version values:

- `app/version.txt` is the source version embedded by qmake into
  `CFBundleShortVersionString` and `CFBundleVersion`.
- The optional second argument to `generate-dmg.sh` is the package version. It
  controls the `Vibertemis-VERSION.dmg` and `Vibertemis-VERSION.dsym` filenames
  and `build_info_macos.txt`, but does not rewrite
  the source or plist version.
- `verify-macos-bundle.sh` checks the plist against `app/version.txt` by
  default. Set `ARTEMIS_EXPECTED_BUNDLE_VERSION` only when intentionally
  verifying a bundle built with a different embedded version.

Development packages are not Developer ID signed or notarized unless signing
and notary credentials are supplied. `macdeployqt` may leave ad-hoc signatures
on bundled code, but those are not a distributable Developer ID signature and
do not avoid Gatekeeper warnings. For a trusted local build, use **Open** from
the Finder context menu or approve it in **System Settings > Privacy &
Security**; clearing quarantine with `xattr -cr Vibertemis.app` is another explicit
local-only option.

Report macOS validation at three independent levels:

1. **Build/test verified:** the clean qmake build and unit tests pass.
2. **Package/launch verified:** the deployed bundle passes the Cocoa smoke test,
   the DMG mounts, and its copy passes the same checks.
3. **Real-host stream verified:** a user completes a stream against Apollo,
   Sunshine, or Vibepollo on actual hardware.

CI establishes the first two levels. It must not be described as stream
verification; the third level remains a manual host-and-network acceptance
test.

## 📋 Development Phases

### ✅ Phase 0: Foundation (COMPLETED)
- [x] Fork Moonlight Qt repository
- [x] Set up CI/CD pipeline for multi-platform builds
- [x] Create enhanced build system (AppImage, Flatpak, Steam Deck)
- [x] Set up development environment scripts
- [x] Create basic project structure for Artemis features
- [x] Implement settings management system
- [x] Update project branding and documentation

### ✅ Phase 1: Research & Protocol Analysis (COMPLETED)
- [x] **Study Artemis Android Implementation**
  - [x] Analyze clipboard sync implementation
  - [x] Understand server commands protocol  
  - [x] Examine OTP pairing mechanism
  - [x] Document protocol differences from standard GameStream
- [x] **Apollo Server Analysis**
  - [x] Study server-side protocol extensions
  - [x] Understand new endpoints and data formats
  - [x] Document authentication mechanisms
- [x] **Protocol Documentation**
  - [x] Create protocol specification document
  - [x] Map Android implementations to Qt architecture
  - [x] Design C++/Qt implementation approach

### ✅ Phase 2: Foundation Implementation (COMPLETED)
- [x] **Core Architecture**
  - [x] Implement ClipboardManager with real Artemis protocol
  - [x] Implement OTPPairingManager with SHA-256 authentication
  - [x] Implement ServerCommandManager with permission system
  - [x] Update all components to match Android implementation
- [x] **Protocol Implementation**
  - [x] HTTP clipboard sync (`/actions/clipboard?type=text`)
  - [x] OTP pairing with `&otpauth=` parameter
  - [x] Apollo server detection and permission checking
  - [x] Smart sync logic and loop prevention

### 🔄 Phase 3: Integration & Testing (IN PROGRESS)
- [ ] **Moonlight Qt Integration**
  - [ ] Extend NvHTTP class with clipboard endpoints
  - [ ] Extend NvPairingManager with OTP support
  - [ ] Extend NvComputer with Apollo permission tracking
  - [ ] Integrate managers with existing session flow
- [ ] **UI Implementation**
  - [ ] Create clipboard sync settings UI
  - [ ] Create OTP pairing dialog
  - [ ] Create server commands menu
  - [ ] Add Apollo server indicators
- [ ] **Testing & Validation**
  - [ ] Test with Apollo server
  - [ ] Validate protocol compatibility
  - [ ] Test error handling and edge cases

### 📋 Key Findings from Artemis Android Analysis

#### Clipboard Sync Implementation
- **HTTP Endpoints**: Uses `actions/clipboard` endpoint with `type=text` parameter
- **Methods**: `getClipboard()` and `sendClipboard(content)` in NvHTTP.java
- **Protocol**: Simple HTTP GET/POST to Apollo server
- **Smart Sync**: Automatic sync on stream start/resume and focus loss
- **Identifier**: Uses `CLIPBOARD_IDENTIFIER` to avoid sync loops
- **Settings**: `smartClipboardSync`, `smartClipboardSyncToast`, `hideClipboardContent`

#### OTP Pairing Implementation  
- **Protocol**: Extends standard PIN pairing with `&otpauth=` parameter
- **Hash**: SHA-256 hash of `pin + saltStr + passphrase`
- **UI**: 4-digit PIN input, only available with Apollo servers
- **Flow**: Standard pairing flow but with OTP authentication instead of PIN display

#### Server Commands Implementation
- **Permission**: Requires `server_cmd` permission from Apollo server
- **UI**: Menu option "Server Commands" in game menu
- **Error Handling**: Shows dialog if no commands available or permission denied
- **Apollo Only**: Feature only works with Apollo server software

#### Permission System
- **Enum**: `ComputerDetails.Operations` defines permission flags
- **Flags**: `clipboard_set`, `clipboard_read`, `file_upload`, `file_download`, `server_cmd`
- **Check**: Client checks server capabilities before showing features

### 🚧 Phase 2: Core Features Implementation (PLANNED)
- [ ] **Clipboard Sync**
  - [ ] Implement clipboard monitoring
  - [ ] Add network protocol handling
  - [ ] Create bidirectional sync
  - [ ] Add security and size limits
- [ ] **Server Commands**
  - [ ] Implement command execution framework
  - [ ] Add UI for command management
  - [ ] Create command history and favorites
- [ ] **OTP Pairing**
  - [ ] Implement OTP generation and verification
  - [ ] Add enhanced security features
  - [ ] Create user-friendly pairing flow

### 📅 Phase 3: Client-Side Enhancements (FUTURE)
- [ ] **Fractional Refresh Rate Control**
- [ ] **Resolution Scaling Options**
- [ ] **Virtual Display Management**

### 🚀 Phase 4: Advanced Features (FUTURE)
- [ ] **Custom App Ordering**
- [ ] **Server Permission Viewing**
- [ ] **Input-Only Mode**

## 📊 Current Status

**Overall Progress**: 40% Complete  
**Current Phase**: Phase 3 - Integration & Testing  
**Active Development**: Moonlight Qt integration  

## 📝 Development Log

### 2024-12-19: Foundation Implementation Complete ✅
**Major Milestone**: Completed real protocol implementation based on Artemis Android analysis

**Implemented Components:**
- **ClipboardManager**: Real HTTP clipboard sync with `/actions/clipboard?type=text`
  - Smart sync (auto-upload on stream start/resume, auto-download on focus loss)
  - Loop prevention using SHA-256 content hashing
  - All Android settings (smart sync, toast notifications, hide content)
  - 1MB size limit (matches Android)

- **OTPPairingManager**: Real OTP authentication with SHA-256 hashing
  - `SHA256(pin + salt + passphrase)` for `&otpauth=` parameter
  - 4-digit PIN validation (matches Android constraint)
  - Apollo server detection and validation
  - Integration framework for existing NvPairingManager

- **ServerCommandManager**: Apollo permission system and command framework
  - `server_cmd` permission checking (matches Android ComputerDetails.Operations)
  - Apollo server detection and validation
  - Command execution framework with error handling
  - "No Commands Available" dialog (matches Android behavior)

**Protocol Analysis:**
- Analyzed 15+ source files from Artemis Android repository
- Documented real HTTP endpoints and authentication methods
- Created comprehensive protocol specification document
- Mapped Android implementations to Qt architecture

**Key Achievements:**
- ✅ Real protocol implementation (not placeholder)
- ✅ Matches Android behavior exactly
- ✅ Modular architecture for easy integration
- ✅ Comprehensive error handling and validation
- ✅ Apollo server capability detection
- ✅ Permission-based feature enabling

**Next Phase**: Integration with existing Moonlight Qt codebase

### Recently Completed
- ✅ Enhanced CI/CD pipeline with Steam Deck builds
- ✅ Development setup automation
- ✅ Basic class structure for Artemis features
- ✅ Settings management system
- ✅ Project documentation and branding

### Currently Working On
- ✅ **Analyzed Artemis Android source code** (artemis-android repository)
- ✅ **Implemented real protocol foundations** based on Android analysis
- ✅ **Created ClipboardManager** with HTTP clipboard sync
- ✅ **Created OTPPairingManager** with SHA-256 authentication
- ✅ **Created ServerCommandManager** with Apollo permission system
- 🔄 **Ready for Moonlight Qt integration**

### Next Steps
1. **Create feature branch**: `feature/nvhttp-clipboard-endpoints`
2. Extend NvHTTP class with clipboard endpoints (`sendClipboardContent`, `getClipboardContent`)
3. **Create feature branch**: `feature/nvpairing-otp-support`
4. Extend NvPairingManager with OTP support (add `&otpauth=` parameter)
5. **Create feature branch**: `feature/nvcomputer-apollo-permissions`
6. Extend NvComputer with Apollo permission tracking (`apolloOperations` field)
7. **Create feature branch**: `feature/session-manager-integration`
8. Integrate managers with existing session management
9. **Create feature branch**: `feature/artemis-ui-components`
10. Create UI components for settings and dialogs
11. **Create feature branch**: `feature/apollo-server-testing`
12. Test with actual Apollo server

### Git Workflow
- **Feature branches**: `feature/*` → PR to `development` → PR to `main`
- **Bug fixes**: `fix/*` → PR to `development` → PR to `main`
- **Hotfixes**: `hotfix/*` → PR to `main` (critical only)

## 🛠️ Technical Architecture

### Created Components
```
app/backend/
├── clipboardmanager.*      # Clipboard sync (framework created)
├── servercommandmanager.*  # Server commands (framework created)
└── otppairingmanager.*     # OTP pairing (framework created)

app/settings/
└── artemissettings.*       # Centralized settings (implemented)

scripts/
├── setup-dev.sh           # Development environment setup
└── build-*.sh             # Platform-specific build scripts
```

### Integration Points
- Settings system integrated with Qt's QSettings
- Backend managers designed as QObject-based services
- QML integration ready for UI components
- Logging categories defined for debugging

## 🔍 Research Notes

### Artemis Android Analysis
*Location*: `../artemis-android` (local clone)

**TODO**: Examine the following areas:
- Clipboard sync implementation files
- Server command execution mechanism
- OTP pairing protocol and UI
- Network protocol extensions
- Security and authentication handling

### Key Questions to Answer
1. **Clipboard Sync**: What network protocol is used? How is data formatted and secured?
2. **Server Commands**: What HTTP endpoints are used? How are commands defined and executed?
3. **OTP Pairing**: How does it differ from PIN pairing? What crypto is used?
4. **Apollo Integration**: What server-side changes are required?

## 📝 Development Log

### 2024-12-19
- Set up comprehensive CI/CD pipeline
- Created development environment automation
- Implemented basic framework for Artemis features
- Created settings management system
- Updated project documentation and branding
- **NEXT**: Begin analysis of Artemis Android source code

### Previous Sessions
- Forked Moonlight Qt repository
- Set up initial project structure
- Configured GitHub Actions for multi-platform builds

## 🎯 Success Criteria

### Phase 1 Complete When:
- [ ] All Artemis Android implementations are understood
- [ ] Protocol specifications are documented
- [ ] Implementation plan is created and approved
- [ ] Test environment is set up with Apollo server

### Phase 2 Complete When:
- [ ] Clipboard sync works bidirectionally
- [ ] Server commands can be executed from Qt client
- [ ] OTP pairing works with Apollo server
- [ ] All features tested on multiple platforms

## 📞 Resources & References

- **Artemis Android**: https://github.com/ClassicOldSong/moonlight-android
- **Apollo Server**: https://github.com/ClassicOldSong/Apollo
- **Moonlight Qt**: https://github.com/moonlight-stream/moonlight-qt
- **Development Guide**: [docs/DEV_GUIDE.md](docs/DEV_GUIDE.md)
- **GameStream Protocol**: https://github.com/moonlight-stream/moonlight-docs/wiki/GameStream-Protocol
