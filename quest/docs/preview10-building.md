# Preview 10 corresponding source

- android/: patched Moonlight XR app, submodules, the exact three custom
  native libraries packaged in the APK, and the pinned NDK libc++_shared.so
  runtime now packaged.
- alvr-source/: complete patched ALVR 20.14.1-vibertemis-pyro.1 and OpenVR SDK.
- pyrowave-source/: pinned PyroWave and Granite, with required submodules.
- vibertemis-quest/: overlay, native bridge, patches, build scripts, companion,
  source pins, license and user documentation.
- third-party-licenses/: notices for prebuilt Android dependencies.

To rebuild the Android app, install Android SDK 34/build tools 34.0.0,
NDK 27.0.12077973 and JDK 21. Set ANDROID_HOME/JAVA_HOME, then from android/
run:

    ./gradlew testNonRootDebugUnitTest assembleNonRootDebug

Dependencies are downloaded by Gradle. For a clean native rebuild, follow the
native build steps in vibertemis-quest/quest/README.md. The C++ runtime is
supplied by the installed, pinned NDK; our APK script does not rebuild it.

After the generated upstream tree and custom native libraries are prepared,
run quest/build/build.sh from vibertemis-quest/. It stages the NDK runtime,
emits ndk-runtime-provenance.txt and ndk-runtime-NOTICE.txt under
build/quest/upstream/app/build/, runs the Android tests and APK assembly,
and checks the packaged native dependency closure. Copies of both sidecars
are included at this source archive's root.

For a direct Android Gradle rebuild, the bundled native libraries and the
pinned NDK libc++ shared runtime are retained. After assembling, verify the
packaged native dependency closure:

    python3 ../vibertemis-quest/quest/build/verify-apk-native.py app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk

For a clean full native rebuild, follow quest/README.md. Its scripts fetch the
exact source revisions specified in quest/native/sources.json, apply the
included native patch, build native libraries and install a hash-verified
matching set. Rust 1.97.1, CMake and Ninja are required. Native builds
require Internet access for Cargo/build dependencies. The complete custom native sources used for this release are included above
for inspection and modification. The NDK C++ runtime is provided as a prebuilt
binary with provenance and notices; the full NDK source archive is not included.

For Windows use quest/native/build-windows.ps1 with MSVC, Windows SDK,
CMake 3.27+ and Rust 1.97.1. From quest/host, Go 1.26.8 builds the companion:

    go build ./cmd/vibertemis-host-companion

Original component licenses remain in their source trees. GPLv3 app source
and build/installation scripts are provided here alongside MIT native code.
Hardware streaming has not been validated on Quest 3 / RTX 4090.

Windows manager/installer: .NET 8 and Inno Setup 6 are required. From
vibertemis-quest run quest/installer/build-installer.ps1 on Windows. The
script verifies the immutable native ZIP, stages binaries, embeds hashes, and
bundles license notices. See quest/update/README.md for signed release
metadata. Private release and APK signing keys are not provided; original
signing keys are not included, and a new debug key cannot update an installed
APK signed with a different key.

Native fingerprint: 512e3203110ed3cbc822cf456e36e239dcd3bcb764feca62c316d910a37764f2
(same as preview 9). Native protocol: 20.14.1-vibertemis-pyro.1.