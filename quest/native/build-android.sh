#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
sdk="${ANDROID_HOME:?ANDROID_HOME is required}"
ndk="$sdk/ndk/27.0.12077973"
pyro="$repo/build/quest/pyrowave"
alvr="$repo/build/quest/alvr"
bridge="$repo/build/quest/pyroclient"
python3 "$repo/quest/native/fetch.py"
cmake -S "$pyro" -B "$pyro/build-android" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_shared \
  -DPYROWAVE_DEVEL=OFF -DPYROWAVE_FP32_MATH=ON -DCMAKE_BUILD_TYPE=Release
cmake --build "$pyro/build-android" --target pyrowave-shared --parallel 4
cmake -S "$repo/quest/native/pyroclient" -B "$bridge" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_shared \
  -DPYROWAVE_SOURCE="$pyro" -DPYROWAVE_LIBRARY="$pyro/build-android/libpyrowave-shared.so" -DCMAKE_BUILD_TYPE=Release
cmake --build "$bridge" --parallel 4
# NDK host tools: this script currently supports a Linux build host.
llvm="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
export PYROCLIENT_LIB_DIR="$bridge"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$llvm/aarch64-linux-android30-clang"
export CC_aarch64_linux_android="$llvm/aarch64-linux-android30-clang"
export CXX_aarch64_linux_android="$llvm/aarch64-linux-android30-clang++"
export AR_aarch64_linux_android="$llvm/llvm-ar"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$alvr/target}"
cargo +1.97.1 build --manifest-path "$alvr/Cargo.toml" --locked --release --target aarch64-linux-android -p alvr_client_openxr
output="$repo/build/quest/native-android"
mkdir -p "$output"
cp "$CARGO_TARGET_DIR/aarch64-linux-android/release/libalvr_client_openxr.so" "$output/"
cp -L "$bridge/libpyroclient.so" "$pyro/build-android/libpyrowave-shared.so" "$output/"
"$llvm/llvm-strip" --strip-unneeded "$output/"*.so
(cd "$output" && sha256sum *.so > SHA256SUMS)
printf 'Native libraries built in %s; APK overlay step must install this matched set.\n' "$output"
