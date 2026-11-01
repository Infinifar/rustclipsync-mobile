#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
case "$(uname -s)" in
  Darwin) lib=target/debug/librustclipsync_mobile_ffi.dylib ;;
  Linux) lib=target/debug/librustclipsync_mobile_ffi.so ;;
  *) echo 'Host smoke tests require Linux or macOS' >&2; exit 1 ;;
esac
cargo build --locked -p rustclipsync-mobile-ffi
cargo run --locked -p mobile-bindgen -- generate --library "$lib" --language python --no-format --out-dir build/python
cp "$lib" build/python/
python3 scripts/test-bindings.py
