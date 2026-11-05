#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
ndk_bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -x "$ndk_bin/aarch64-linux-android30-clang" ]]; then
    echo 'Install Android NDK 27.2.12479018 (Linux x86_64 host required by this script).' >&2
    exit 1
fi
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndk_bin/aarch64-linux-android30-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$ndk_bin/llvm-ar"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$ndk_bin/x86_64-linux-android30-clang"
export CC_x86_64_linux_android="$CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER"
export AR_x86_64_linux_android="$ndk_bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
export CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
for target in aarch64-linux-android x86_64-linux-android; do
    cargo build --release --locked -p yamusic-core --target "$target"
done
mkdir -p android/app/src/main/jniLibs/{arm64-v8a,x86_64}
cp target/aarch64-linux-android/release/libyamusic_core.so android/app/src/main/jniLibs/arm64-v8a/
cp target/x86_64-linux-android/release/libyamusic_core.so android/app/src/main/jniLibs/x86_64/
# Keep exported JNI symbols while removing development-only debug data.
"$ndk_bin/llvm-strip" --strip-unneeded android/app/src/main/jniLibs/arm64-v8a/libyamusic_core.so
"$ndk_bin/llvm-strip" --strip-unneeded android/app/src/main/jniLibs/x86_64/libyamusic_core.so
cd android
if [[ -x ./gradlew ]]; then ./gradlew --no-daemon assembleDebug; else "${GRADLE_BIN:-gradle}" --no-daemon assembleDebug; fi
