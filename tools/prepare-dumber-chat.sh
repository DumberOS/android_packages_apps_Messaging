#!/usr/bin/env bash
# Run before Soong: m does not invoke Cargo or fetch dependencies.
set -euo pipefail
app_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_NDK_HOME:=${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/30.0.16248370}"
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
[[ -x "$toolchain/llvm-ar" ]] || { echo "Set ANDROID_NDK_HOME to an installed NDK" >&2; exit 1; }
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$app_dir/.dumber-target}"
for spec in 'arm64-v8a aarch64-linux-android aarch64-linux-android' 'armeabi-v7a armv7-linux-androideabi armv7a-linux-androideabi'; do
    read -r abi target compiler <<< "$spec"
    key="${target//-/_}"
    env "CARGO_TARGET_${key^^}_LINKER=$toolchain/${compiler}23-clang" \
        "CC_$key=$toolchain/${compiler}23-clang" "CXX_$key=$toolchain/${compiler}23-clang++" \
        "AR_$key=$toolchain/llvm-ar" \
        cargo build --locked --release --manifest-path "$app_dir/vendor/dumber-chat-core/Cargo.toml" --target "$target"
    mkdir -p "$app_dir/native/$abi"
    "$toolchain/llvm-strip" --strip-unneeded -o "$app_dir/native/$abi/libdumber_chat_core.so" \
        "$CARGO_TARGET_DIR/$target/release/libdumber_chat_core.so"
done
