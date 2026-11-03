#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${GITHUB_SHA:?Missing source revision}"
: "${RUNNER_TEMP:?Missing temporary directory}"
test "${GITHUB_REF:-}" = refs/heads/main
version=0.1.5
tag="v$version"
apk="rustclipsync-mobile-$version-android.apk"
previous=$(gh release list --exclude-drafts --exclude-pre-releases --limit 1 --json tagName --jq '.[0].tagName // empty')
if [[ -n "$previous" ]]; then
  mkdir -p "$RUNNER_TEMP/previous-mobile-release"
  gh release download "$previous" --pattern 'rustclipsync-mobile-*-android.apk' --dir "$RUNNER_TEMP/previous-mobile-release"
  shopt -s nullglob
  old=("$RUNNER_TEMP/previous-mobile-release/"*.apk)
  test "${#old[@]}" -eq 1
  tools="$ANDROID_HOME/build-tools/36.0.0"
  "$tools/apksigner" verify --print-certs "${old[0]}" > "$RUNNER_TEMP/previous-mobile-cert.txt"
  previous_cert=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$RUNNER_TEMP/previous-mobile-cert.txt")
  test "$previous_cert" = "$(tr -d '\r\n' < android/release-signing.sha256)"
  old_code=$("$tools/aapt" dump badging "${old[0]}" | sed -n "s/^package: .*versionCode='\([0-9]*\)'.*/\1/p")
  [[ "$old_code" =~ ^[0-9]+$ ]] && (( old_code < 6 ))
fi
gh release create "$tag" release-output/* --draft --target "$GITHUB_SHA" \
  --title "RustClipSync Mobile $version" --notes-file "releases/$version.md"
verify=$(mktemp -d "$RUNNER_TEMP/mobile-published.XXXXXX")
gh release download "$tag" --dir "$verify"
cmp release-output/SHA256SUMS "$verify/SHA256SUMS"
(cd "$verify" && sha256sum --check SHA256SUMS)
gh release edit "$tag" --draft=false --latest
