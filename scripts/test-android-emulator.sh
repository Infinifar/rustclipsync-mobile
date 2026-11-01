#!/usr/bin/env bash
# Run only synthetic binding/gate tests in a fresh, disposable Android 12 emulator.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to an SDK containing emulator and command-line tools}"
app="${1:-android/app/build/outputs/apk/debug/app-debug.apk}"
tests="${2:-android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
[[ -f "$app" && -f "$tests" ]]
mkdir -p build .ci-tools/android-user .ci-tools/avd
export ANDROID_USER_HOME="$PWD/.ci-tools/android-user"
export ANDROID_AVD_HOME="$PWD/.ci-tools/avd"
export ANDROID_SERIAL=emulator-5554
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
sdkmanager 'system-images;android-31;default;x86_64' </dev/null
printf 'no\n' | avdmanager create avd --force --name rcs-synthetic --package 'system-images;android-31;default;x86_64' --device pixel_5
emulator -avd rcs-synthetic -port 5554 -accel on -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 > build/android-emulator.log 2>&1 &
emulator_pid=$!
cleanup() {
  kill "$emulator_pid" 2>/dev/null || true
  wait "$emulator_pid" 2>/dev/null || true
  adb kill-server >/dev/null 2>&1 || true
  rm -rf .ci-tools/android-user .ci-tools/avd
}
trap cleanup EXIT
booted=false
for ((attempt = 0; attempt < 120; attempt++)); do
  if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then booted=true; break; fi
  kill -0 "$emulator_pid" 2>/dev/null || { tail -30 build/android-emulator.log; exit 1; }
  sleep 2
done
[[ "$booted" == true ]] || { tail -30 build/android-emulator.log; echo 'Emulator boot timed out' >&2; exit 1; }
adb shell getprop ro.build.version.sdk
adb install -r "$app"
adb install -r "$tests"
adb shell am instrument -w -r -e class io.github.infinifar.rustclipsync.BindingSmokeTest,io.github.infinifar.rustclipsync.MainThreadGateTest io.github.infinifar.rustclipsync.test/androidx.test.runner.AndroidJUnitRunner | tee build/android-instrumentation.txt
python3 - <<'PY'
import re
from pathlib import Path
report = Path('build/android-instrumentation.txt').read_text()
match = re.search(r'OK \((\d+) tests?\)', report)
if not match or int(match[1]) < 4 or 'INSTRUMENTATION_CODE: -1' not in report:
    raise SystemExit('Synthetic instrumentation tests did not all pass')
print('Synthetic Android instrumentation tests passed:', match[1])
PY
