# macOS build repair design

## Purpose

Restore a truthful, testable macOS development build on Apple Silicon and make
the same build reproducible on the user's macOS 26.5 machine.

The current GitHub failures are compile-time failures in Qt 6.8.3's
`qyieldcpu.h`. With the macOS 26.4 SDK, Apple Clang reports `__has_builtin(__yield)`
as true while `__yield()` has no declaration unless `<arm_acle.h>` is included.
Qt fixed QTBUG-145239 in commit
`a76004f16fdc43e1b7af83bfdf3f1a613491b234` by preferring
`__builtin_arm_yield`. Qt 6.11.1 contains that change and officially supports
macOS 26.

## Toolchain policy

macOS jobs will use a separate patch-stable `QT_VERSION_MACOS=6.11.1` value.
Other jobs retain their existing Qt line. The code must not adopt Qt 6.11-only
APIs; this is a toolchain compatibility fix, not a cross-platform framework
migration.

The workflow and development documentation will call out this temporary skew
and the intention to converge when the common supported Qt line contains the
upstream fix. CI logs the runner image, macOS version, SDK, compiler, Qt, qmake,
and architecture so later image changes are diagnosable.

The project will not patch installed Qt headers or suppress the compiler error.

## Build flow

The first repair is the minimal toolchain pin needed to pass the failing compile
path. Redundant legacy build or manual MOC steps may be removed only after a
clean qmake build proves they are unnecessary.

The macOS job will:

1. check out recursive submodules;
2. install Qt 6.11.1 and the existing FFmpeg, Opus, SDL, SDL_ttf, and packaging
   dependencies;
3. configure and compile a clean release build with the declared deployment
   target;
4. run the refresh-rate unit test from the build tree;
5. deploy Qt frameworks and plugins into `Artemis.app`;
6. launch the deployed app bundle executable under a controlled timeout and
   fail on immediate loader or platform-plugin errors;
7. inspect the deployed executable architecture and unresolved dynamic
   libraries;
8. package the unsigned development app into a DMG;
9. mount the DMG, launch or inspect the app from the mounted image as the runner
   permits, and verify the app bundle and executable; and
10. upload an artifact whose name states the architecture actually found.

The artifact must not be called “Universal” unless `lipo -archs` reports both
`arm64` and `x86_64`. An Apple Silicon-only result is named accordingly. A
future Intel or true universal build is outside this repair unless the existing
dependency set already provides both slices without new packaging machinery.

## Local Mac validation

Documentation will provide exact commands to install or locate Qt 6.11.1,
install Homebrew dependencies, initialize submodules, run qmake and make, deploy
the app, launch it, and generate the DMG. It will include commands to inspect
architecture and linked libraries.

Local validation has three distinct levels:

- **build verified:** source compiles and links;
- **package/launch verified:** the app bundle launches and the DMG mounts; and
- **stream verified:** the user connects to a real Apollo, Sunshine, or
  Vibepollo host and completes a stream.

CI can establish the first two levels. Documentation and release notes must not
claim the third until the user performs that acceptance test. Unsigned
development-build Gatekeeper/quarantine instructions remain explicit.

## Testing and acceptance

- Reproduce the original Qt 6.8.3 error from existing CI logs and preserve a
  link to QTBUG-145239/the fixing commit in workflow comments or docs.
- Prove the selected Qt headers contain the fixed intrinsic ordering.
- Run a clean qmake compile rather than relying on stale generated files.
- Run unit tests that do not need a display.
- Launch the packaged executable under a timeout and fail on immediate loader
  or plugin errors.
- Use `lipo`, `otool`, and bundle inspection to verify architecture and dylibs.
- Mount the DMG in CI and verify `Artemis.app/Contents/MacOS/Artemis` exists and
  is executable.
- Re-run the same build and launch checks locally on this Apple Silicon Mac.

## Failure handling

Toolchain installation, compile, launch, dependency, deployment, or DMG checks
fail their job rather than producing a misleading artifact. A host connection
is not required for CI, and inability to perform a live stream must be reported
as an unverified acceptance item rather than hidden by a smoke test.
