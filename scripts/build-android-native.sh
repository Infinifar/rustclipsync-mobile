#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK root}"
ndk="$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt"
case "$(uname -s)" in
  Linux) ndk="$ndk/linux-x86_64" ;;
  Darwin) ndk="$ndk/darwin-x86_64" ;;
  *) echo 'Unsupported NDK host' >&2; exit 1 ;;
esac
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndk/bin/aarch64-linux-android31-clang"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$ndk/bin/llvm-ar"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$ndk/bin/x86_64-linux-android31-clang"
export CC_x86_64_linux_android="$CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER"
export AR_x86_64_linux_android="$ndk/bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
export CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
for target in aarch64-linux-android x86_64-linux-android; do
  cargo build --locked --release -p rustclipsync-mobile-ffi --target "$target"
  case "$target" in aarch64*) abi=arm64-v8a ;; x86_64*) abi=x86_64 ;; esac
  mkdir -p "android/app/src/main/jniLibs/$abi"
  cp "target/$target/release/librustclipsync_mobile_ffi.so" "android/app/src/main/jniLibs/$abi/"
  "$ndk/bin/llvm-readelf" -lW "android/app/src/main/jniLibs/$abi/librustclipsync_mobile_ffi.so" | awk '/LOAD/ {print; if ($NF != "0x4000") exit 1}'
done
