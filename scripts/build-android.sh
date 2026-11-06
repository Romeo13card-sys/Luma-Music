#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
ndk_bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
target_dir="${CARGO_TARGET_DIR:-target}"
for entry in \
    'armv7-linux-androideabi:armv7a-linux-androideabi30:armeabi-v7a' \
    'aarch64-linux-android:aarch64-linux-android30:arm64-v8a' \
    'i686-linux-android:i686-linux-android30:x86' \
    'x86_64-linux-android:x86_64-linux-android30:x86_64'; do
    IFS=: read -r target compiler abi <<< "$entry"
    if [[ ! -x "$ndk_bin/$compiler-clang" ]]; then
        echo "Missing NDK compiler: $compiler-clang" >&2
        exit 1
    fi
    cargo_key="${target^^}"
    cargo_key="${cargo_key//-/_}"
    cc_key="${target//-/_}"
    export "CARGO_TARGET_${cargo_key}_LINKER=$ndk_bin/$compiler-clang"
    export "CARGO_TARGET_${cargo_key}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384"
    export "CC_${cc_key}=$ndk_bin/$compiler-clang"
    export "AR_${cc_key}=$ndk_bin/llvm-ar"
    cargo build --release --locked -p yamusic-core --target "$target"
    mkdir -p "android/app/src/main/jniLibs/$abi"
    cp "$target_dir/$target/release/libyamusic_core.so" "android/app/src/main/jniLibs/$abi/"
    "$ndk_bin/llvm-strip" --strip-unneeded "android/app/src/main/jniLibs/$abi/libyamusic_core.so"
done
cd android
case "${LUMA_ANDROID_BUILD_TYPE:-release}" in
    release) task=assembleRelease ;;
    debug) task=assembleDebug ;;
    *) echo 'LUMA_ANDROID_BUILD_TYPE must be release or debug' >&2; exit 1 ;;
esac
if [[ -x ./gradlew ]]; then ./gradlew --no-daemon "$task"; else "${GRADLE_BIN:-gradle}" --no-daemon "$task"; fi
