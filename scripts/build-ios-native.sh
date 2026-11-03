#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "$(uname -s)" != Darwin ]] || ! xcrun --find clang >/dev/null 2>&1; then
  echo 'iOS native build requires macOS and full Xcode; no iOS validation performed.' >&2
  exit 1
fi
export IPHONEOS_DEPLOYMENT_TARGET=18.0
scripts/generate-bindings.sh
mkdir -p ios/Generated build/ios-headers
cp build/bindings/RustClipSync.swift build/bindings/RustClipSyncFFI.modulemap ios/Generated/
cp build/bindings/RustClipSyncFFI.h build/ios-headers/
cp build/bindings/RustClipSyncFFI.modulemap build/ios-headers/module.modulemap
for target in aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios; do
  cargo build --locked --release -p rustclipsync-mobile-ffi --target "$target"
done
xcrun lipo -create target/aarch64-apple-ios-sim/release/librustclipsync_mobile_ffi.a target/x86_64-apple-ios/release/librustclipsync_mobile_ffi.a -output build/ios-simulator.a
# Replace only this generated framework so the script can be run repeatedly.
rm -rf ios/Generated/RustClipSyncFFI.xcframework
xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/librustclipsync_mobile_ffi.a -headers build/ios-headers \
  -library build/ios-simulator.a -headers build/ios-headers \
  -output ios/Generated/RustClipSyncFFI.xcframework
cp build/bindings/RustClipSyncFFI.h ios/Generated/
