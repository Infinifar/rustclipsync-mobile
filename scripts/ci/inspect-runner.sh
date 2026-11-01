#!/usr/bin/env bash
set -euo pipefail
# Print only toolchain metadata; never enumerate environment variables or credentials.
uname -srm
for executable in java javac cargo rustup python3 sdkmanager; do
  command -v "$executable" || true
done
java -version 2>&1 || true
cargo --version || true
if [[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  sdk="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
  printf 'SDK root: %s\n' "$sdk"
  for component in platforms build-tools ndk; do
    printf '%s:\n' "$component"
    ls "$sdk/$component" 2>/dev/null || true
  done
fi
