#!/usr/bin/env bash
set -euo pipefail

android_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
module_root="$android_root/native/nova-core-rust"
ghostty_root="$android_root/native/nova-ghostty-zig"
output="${1:-$android_root/app/build/generated/novaCore/jniLibs}"

: "${ANDROID_SDK_ROOT:=${ANDROID_HOME:-$HOME/Android/Sdk}}"
: "${ANDROID_NDK_HOME:=$ANDROID_SDK_ROOT/ndk/28.2.13676358}"
export ANDROID_NDK_HOME
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

if [[ ! -x "$toolchain/aarch64-linux-android28-clang" ]]; then
    echo "Android NDK r28c toolchain was not found at $toolchain" >&2
    exit 1
fi

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$toolchain/aarch64-linux-android28-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$toolchain/x86_64-linux-android28-clang"
export CC_aarch64_linux_android="$toolchain/aarch64-linux-android28-clang"
export CC_x86_64_linux_android="$toolchain/x86_64-linux-android28-clang"
export AR_aarch64_linux_android="$toolchain/llvm-ar"
export AR_x86_64_linux_android="$toolchain/llvm-ar"
export CARGO_ENCODED_RUSTFLAGS="-Clink-arg=-Wl,-z,max-page-size=16384,--no-undefined"

zig_bin="$("$android_root/scripts/ensure-zig.sh")"
for abi in arm64-v8a x86_64; do
    (
        cd "$ghostty_root"
        "$zig_bin" build \
            -Dabi="$abi" \
            -Dandroid-api=28 \
            -Doptimize=ReleaseSmall \
            --prefix "zig-out/$abi"
    )
done

cd "$module_root"
NOVA_GHOSTTY_LIB_DIR="$ghostty_root/zig-out/arm64-v8a/lib" \
    cargo build --locked --release --features ghostty --target aarch64-linux-android
NOVA_GHOSTTY_LIB_DIR="$ghostty_root/zig-out/x86_64/lib" \
    cargo build --locked --release --features ghostty --target x86_64-linux-android

rm -rf "$output"
mkdir -p "$output/arm64-v8a" "$output/x86_64"
cp "target/aarch64-linux-android/release/libnova_core.so" "$output/arm64-v8a/"
cp "target/x86_64-linux-android/release/libnova_core.so" "$output/x86_64/"
sha256sum "$output/arm64-v8a/libnova_core.so" "$output/x86_64/libnova_core.so"
