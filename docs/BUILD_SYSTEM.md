# Vibertemis build system guide

## Current status

The source of truth for automated builds is
[`dev-build.yml`](../.github/workflows/dev-build.yml). It builds development
artifacts when the workflow's change gate selects a build. Availability and
support must be judged from the job for the exact commit; this guide does not
claim every theoretical target is production-ready.

## Current CI artifacts

Public artifacts use Vibertemis names unless an established identifier is kept
explicitly for compatibility:

| Job | Current public artifact | Status |
| --- | --- | --- |
| Windows x64 portable | `vibertemis-windows-x64-portable-{version}.zip` | Enabled when the build gate is true |
| Windows ARM64 portable | `vibertemis-windows-arm64-portable-{version}.zip` | Enabled when the build gate is true |
| Windows universal bundle | `vibertemis-windows-universal-installer-{version}.exe` | Enabled; wraps the internal x64 and ARM64 MSI packages |
| macOS | `Vibertemis-{version}.dmg`, uploaded as `vibertemis-macos-{architecture}-{version}` | Enabled; native host architecture by default |
| Linux archive | `vibertemis-linux-{version}.tar.gz` | Enabled |
| Raspberry Pi ARM64 | `vibertemis-raspberry-pi-arm64-{version}.zip` | Enabled |
| Flatpak | `artemis-flatpak-{version}.flatpak` | Enabled compatibility artifact |
| Rolling Steam Deck | `artemis-steam-deck.flatpak`, checksum, and atomic archive | Enabled compatibility release assets |
| AppImage | No current artifact | AppImage job is intentionally disabled with `if: false` |

The Flatpak application ID `com.artemisdesktop.ArtemisDesktopDev`, the
`artemis-flatpak-*` handoff artifact, and the `artemis-steam-deck.*` rolling
assets remain established compatibility contracts. Their names do not describe
the product shown in the UI, which is Vibertemis.

## Architecture and packaging boundaries

- Windows CI builds native x64 and cross-compiled ARM64 portable archives. The
  universal `.exe` bundle selects its internal architecture-specific MSI.
- macOS packaging builds the current machine architecture unless
  `ARTEMIS_MAC_ARCHS` requests a dependency-complete set of slices. The artifact
  is called `universal` only when `lipo` finds both `arm64` and `x86_64`.
- Linux CI produces an x86_64 archive, the Steam Deck-focused Flatpak, and a
  Raspberry Pi ARM64 archive. These are separate targets, not one universal
  Linux package.
- The AppImage implementation remains in the workflow for future repair, but
  its job does not run and must not be presented as a current download.
- Steam Link scripts are experimental source tooling, not a current CI product.

## Build entry points

The build entry points retain technical Artemis names where the source and
executable compatibility contract requires them:

- `artemis.pro` — qmake project entry point;
- `scripts/build-artemis-arch.bat` — Windows architecture build script;
- `scripts/generate-artemis-bundle.bat` — current Windows universal bundle fallback used by CI;
- `scripts/generate-bundle.bat` — legacy Moonlight-input tooling, not the current Vibertemis path;
- `scripts/generate-dmg.sh` — macOS app/DMG generation; and
- `packaging/flatpak/com.artemisdesktop.ArtemisDesktopDev.json` — development
  Flatpak manifest.

`scripts/build-appimage.sh` and the disabled workflow job are not evidence that
an AppImage is currently produced.

## Local examples

### Windows portable build

Use a Visual Studio 2022 developer environment and a matching Qt architecture:

```batch
scripts\build-artemis-arch.bat release
```

The script builds the compatibility-named internal `Artemis.exe`; CI renames
the outer portable archive to the public `vibertemis-windows-*-portable-*`
form.

### macOS DMG

Use Qt 6.11.1 with the current macOS SDK and follow the complete toolchain and
verification instructions in [DEVELOPMENT.md](DEVELOPMENT.md#macos-development-builds):

```bash
./scripts/generate-dmg.sh Release
```

The script produces `Vibertemis.app` and `Vibertemis-{version}.dmg`. It builds
the native architecture by default, not an unconditional universal binary.
Development output is unsigned and unnotarized unless credentials are supplied.

### Basic Unix build

```bash
qmake6 artemis.pro CONFIG+=release
make -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc)"
```

### Flatpak

Use the tracked manifest and tests under `packaging/flatpak/`. The public UI name
is Vibertemis while the application ID and command remain compatibility names.
Steam Deck installation and validation are documented in
[STEAM_DECK.md](STEAM_DECK.md).

## Verification

Do not infer a platform pass from the workflow badge alone. Open the run for the
exact commit and confirm the relevant job ran. macOS uses
`verify-macos-bundle.sh`; Flatpak CI validates metadata, bundled dependencies,
codec capabilities, and a bounded startup. Neither software-only job proves a
real host stream or physical Steam Deck hardware behavior.
