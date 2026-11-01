#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/verify-vendor.py
cargo fmt --all --check
cargo clippy --workspace --locked --all-targets --all-features -- -D warnings
cargo test --workspace --locked
cargo doc --workspace --locked --no-deps
scripts/generate-bindings.sh
scripts/test-bindings.sh
