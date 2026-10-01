# Quest C++ runtime packaging

The preview9 APK omitted `libc++_shared.so`, although both
`libpyroclient.so` and `libpyrowave-shared.so` require it. Android does
not supply this NDK shared runtime as a system library.

`quest/build/build.sh` stages the arm64 runtime from NDK `27.0.12077973`,
the toolchain already pinned for the native build:

```
toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so
```

The build records the source and stripped runtime SHA-256 hashes in
`build/quest/upstream/app/build/ndk-runtime-provenance.txt`. Its mandatory
APK verifier checks the packaged runtime against the staged hash, every
native library's ELF dependency closure, the required dynamically loaded
OpenXR loader, ABI, duplicate entries, and malformed ELF metadata.
Native codec sources, their existing three-binary manifest, and the wire
protocol remain unchanged.

Run the verifier regression suite:

```
python3 -m unittest discover -s quest/build/tests -v
```

Set `VIBERTEMIS_RELEASED_APK` to the original preview9 APK to also run the
historical artifact regression. That regression must identify exactly the
missing C++ runtime and its two unresolved dependencies. Synthetic ELF
fixtures verify both successful closure and distinct packaging failures.
They do not replace verification of the final built APK or headset testing.

## Pinned revision and runtime attribution

`source.properties` in the installed NDK confirms the pinned revision:

```
Pkg.Revision = 27.0.12077973
```

The runtime staged into `lib/arm64-v8a/libc++_shared.so` is copied from the
pinned NDK and then stripped with the same toolchain's `llvm-strip
--strip-unneeded`. The packaged (stripped) runtime hashes to:

```
sha256  92ee8da641b969254cc8723f64aa115d36c8ffa87115e38bc9cb89e3eefee839
```

The mandatory APK verifier ran its full regression suite for this change:
11 tests passed, including the released preview9 negative case that must
identify the missing C++ runtime and its two unresolved dependencies.

## Release prerequisites

The build emits two sidecars next to the APK outputs, and the final release
must include both:

1. `ndk-runtime-provenance.txt` — pinned NDK revision, runtime source path,
   runtime ABI/SONAME, and the source and stripped SHA-256 hashes.
2. `ndk-runtime-NOTICE.txt` — the pinned NDK's `NOTICE` and
   `NOTICE.toolchain` concatenated verbatim under explicit section labels,
   so the required attribution ships with the packaged runtime. This step is
   fail-closed: if either notice file is missing from the pinned NDK, the
   build aborts instead of shipping an unattributed runtime.

Completed for this change:

1. The actual native-only APK build completed, with the 279 baseline Android
   tests passing (0 failures, 0 skipped).
2. The APK verifier passed against that actual APK: complete dependency
   closure for every native library, plus an exact match on the stripped
   runtime hash recorded above.

Still required before release:

1. Retain the runtime provenance and notice sidecars alongside the release
   artifacts.
2. Integrated release validation for the new hub/updater remains separate
   work and is pending. It is not covered by any result above and is not
   claimed here.
3. Test the native launch on Quest. Static dependency checks cannot confirm
   successful streaming on hardware, so streaming validation requires a
   hardware test. This does not block distribution: an experimental
   ready-to-test prerelease may be built and distributed before the user
   hardware test is run.
