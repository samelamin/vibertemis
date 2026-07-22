# Vibertemis Windows ARM64 Support

Vibertemis has a native Windows ARM64 development build for Windows 11 on ARM
devices such as Surface Pro X-class hardware, Windows Dev Kit 2023, and ARM64
virtual machines.

## Current artifacts

For a commit whose Windows jobs ran successfully, CI uploads:

- `vibertemis-windows-arm64-portable-{version}.zip` — the public native ARM64
  portable archive;
- `vibertemis-windows-x64-portable-{version}.zip` — the public x64 portable
  archive; and
- `vibertemis-windows-universal-installer-{version}.exe` — the public WiX bundle
  containing both architectures.

There is no separately published public ARM64 MSI artifact in the current
workflow. The universal installer consumes compatibility-named internal
`Artemis.msi`, `Artemis_arm64.msi`, and `Artemis_x64.msi` packages. Likewise,
the installed program remains the internal `Artemis.exe` executable. These are
source and upgrade compatibility names, not the public product name.

## Compatibility identities

Do not rename the internal executable, MSI source paths, WiX source directories,
upgrade identifiers, or registry keys as part of a documentation rebrand. In
particular, the WiX source still uses `wix/Artemis`, `wix/ArtemisSetup`, and the
registry key `Software\Artemis Desktop Project` so existing installer state and
upgrade behavior remain coherent. User-visible product labels and outer CI
artifacts are Vibertemis.

## Toolchain

The workflow installs Qt's `win64_msvc2022_arm64` package and uses the Visual
Studio `amd64_arm64` cross-compilation environment. A local build needs:

1. Visual Studio 2022 with ARM64 C++ build tools;
2. the Qt version and ARM64 modules pinned by the workflow; and
3. WiX Toolset v5 only when building the universal installer.

From a configured developer command prompt:

```batch
scripts\build-artemis-arch.bat release
```

The compatibility-named script detects the ARM64 Qt installation, selects the
x64 host tools where necessary, runs `windeployqt`, and stages the internal
`Artemis.exe`. CI then gives the outer archive its `vibertemis-*` public name.

## Testing

Prefer native Windows 11 ARM64 hardware or a Windows 11 ARM64 VM. Verify the
binary architecture, launch and pairing, hardware decode on the actual GPU,
controller/input behavior, and a real stream. An x64 Vibertemis build may run
under Windows emulation on ARM64, but that does not validate the native ARM64
artifact.

The presence of a CI artifact establishes that the corresponding job packaged
successfully; it does not by itself establish performance or hardware decoder
support on every ARM64 device.

## Troubleshooting

- Install the Visual C++ 2022 ARM64 Redistributable if the portable build reports
  missing runtime DLLs.
- Confirm the selected Qt kit is ARM64 and that the Visual Studio developer
  environment reports the `amd64_arm64` target.
- Compare the exact artifact commit with the workflow run before reporting a
  packaging failure.
- Include the Vibertemis version, Windows build, device/GPU, and a redacted log
  in reports to the [Vibertemis issue tracker](https://github.com/samelamin/vibertemis/issues).
