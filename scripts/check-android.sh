#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME; SDK 36, build-tools 36.0.0 and NDK 28.2.13676358 must be installed}"
command -v java >/dev/null
scripts/generate-bindings.sh
scripts/build-android-native.sh
(cd android && ./gradlew --no-daemon lintDebug assembleDebug assembleDebugAndroidTest)
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 -v 4 android/app/build/outputs/apk/debug/app-debug.apk > build/android-zipalign.txt
case "$(uname -s)" in Linux) host=linux-x86_64 ;; Darwin) host=darwin-x86_64 ;; esac
python3 scripts/check-apk.py android/app/build/outputs/apk/debug/app-debug.apk "$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/$host/bin/llvm-readelf" > build/android-elf-alignment.txt
