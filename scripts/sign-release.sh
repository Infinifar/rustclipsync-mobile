#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${KEYSTORE_BASE64:?Missing signing secret}"
: "${KEYSTORE_PASSWORD:?Missing signing secret}"
: "${KEY_ALIAS:?Missing signing secret}"
: "${KEY_PASSWORD:?Missing signing secret}"
: "${RUNNER_TEMP:?Set private temporary directory}"
: "${ANDROID_HOME:?Set Android SDK}"
umask 077
keystore=$(mktemp "$RUNNER_TEMP/mobile-signing.XXXXXX.p12")
aligned=$(mktemp "$RUNNER_TEMP/mobile-aligned.XXXXXX.apk")
trap 'rm -f -- "$keystore" "$aligned"' EXIT
printf '%s' "$KEYSTORE_BASE64" | base64 --decode > "$keystore"
tools="$ANDROID_HOME/build-tools/36.0.0"
unsigned=android/app/build/outputs/apk/release/app-release-unsigned.apk
test -f "$unsigned"
mkdir -p release-output
apk=release-output/rustclipsync-mobile-0.1.5-android.apk
"$tools/zipalign" -P 16 -f 4 "$unsigned" "$aligned"
"$tools/apksigner" sign --ks "$keystore" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD --out "$apk" "$aligned"
"$tools/apksigner" verify --verbose --print-certs "$apk" > "$RUNNER_TEMP/mobile-release-certificate.txt"
expected=$(tr -d '\r\n' < android/release-signing.sha256)
actual=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$RUNNER_TEMP/mobile-release-certificate.txt")
[[ "$actual" =~ ^[0-9a-f]{64}$ && "$actual" == "$expected" ]]
"$tools/aapt" dump badging "$apk" > "$RUNNER_TEMP/mobile-release-badging.txt"
grep -E "^package: name='io\.github\.infinifar\.rustclipsync' versionCode='6' versionName='0\.1\.5'" "$RUNNER_TEMP/mobile-release-badging.txt"
if grep -q '^application-debuggable' "$RUNNER_TEMP/mobile-release-badging.txt"; then
  echo 'Release must not be debuggable' >&2; exit 1
fi
"$tools/zipalign" -c -P 16 -v 4 "$apk" > build/release-zipalign.txt
python3 scripts/check-apk.py "$apk" "$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" > build/release-elf-alignment.txt
cp android/release-signing.pem release-output/
cp android/release-signing.sha256 release-output/
(cd release-output && sha256sum rustclipsync-mobile-0.1.5-android.apk > SHA256SUMS)
printf 'Verified signed Android release, certificate SHA-256: %s\n' "$actual"
