#!/usr/bin/env bash
# Source this file: installs build tools only inside the disposable CI container.
set -euo pipefail
ci_tools="$CI_PROJECT_DIR/.ci-tools"
mkdir -p "$ci_tools"
export CARGO_HOME="$ci_tools/cargo"
export RUSTUP_HOME="$ci_tools/rustup"
export GRADLE_USER_HOME="$ci_tools/gradle"
export PATH="$CARGO_HOME/bin:$PATH"
export CARGO_BUILD_JOBS=4
export CARGO_NET_GIT_FETCH_WITH_CLI=true
if ! command -v python3 >/dev/null || [[ ! -d /usr/lib/jvm/java-17-openjdk-amd64 ]]; then
  apt-get update
  apt-get install -y --no-install-recommends python3 openjdk-17-jdk-headless
fi
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
# An optional network mirror still uses the wrapper's committed official SHA-256.
if [[ -n "${RCS_GRADLE_DISTRIBUTION_URL:-}" ]]; then
  python3 - <<'PYTHON'
import os, re, urllib.parse
from pathlib import Path
url = os.environ['RCS_GRADLE_DISTRIBUTION_URL']
parsed = urllib.parse.urlsplit(url)
if parsed.scheme != 'https' or parsed.username or parsed.password:
    raise SystemExit('Gradle mirror must be credential-free HTTPS')
p = Path('android/gradle/wrapper/gradle-wrapper.properties')
text = p.read_text()
if 'distributionSha256Sum=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78' not in text:
    raise SystemExit('Gradle mirror requires the expected official checksum')
p.write_text(re.sub(r'^distributionUrl=.*$', lambda _: 'distributionUrl=' + url, text, flags=re.MULTILINE))
PYTHON
fi
# Resolve the Gradle distribution/plugins before spending time compiling native code.
(cd android && ./gradlew --no-daemon help)
if [[ ! -x "$CARGO_HOME/bin/rustup" ]]; then
  rustup_url=https://static.rust-lang.org/rustup/dist/x86_64-unknown-linux-gnu/rustup-init
  curl -fsSL --retry 2 --connect-timeout 15 --max-time 180 "$rustup_url" -o "$ci_tools/rustup-init"
  expected="$(curl -fsSL --retry 2 --connect-timeout 15 --max-time 30 "$rustup_url.sha256" | cut -d' ' -f1)"
  printf '%s  %s\n' "$expected" "$ci_tools/rustup-init" | sha256sum -c -
  chmod +x "$ci_tools/rustup-init"
  "$ci_tools/rustup-init" -y --no-modify-path --profile minimal --default-toolchain 1.99.1 --component rustfmt --component clippy
fi
rustup target add aarch64-linux-android x86_64-linux-android
# The base image contains accepted SDK licenses. Never auto-accept additional terms here.
sdkmanager 'ndk;28.2.13676358' </dev/null
java -version
rustc --version
