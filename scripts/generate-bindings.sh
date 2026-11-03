#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
features=()
if [[ "${RCS_DISPOSABLE_INTEGRATION:-0}" == 1 ]]; then features=(--features disposable-tests); fi
cargo build --locked -p rustclipsync-mobile-ffi "${features[@]}"
case "$(uname -s)" in
  Darwin) lib=target/debug/librustclipsync_mobile_ffi.dylib ;;
  Linux) lib=target/debug/librustclipsync_mobile_ffi.so ;;
  *) echo 'Use Linux or macOS to generate bindings' >&2; exit 1 ;;
esac
cargo run --locked -p mobile-bindgen -- generate --library "$lib" --language kotlin --language swift --no-format --out-dir build/bindings
