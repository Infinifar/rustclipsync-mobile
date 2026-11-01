#!/usr/bin/env bash
# Preserve ordinary artifacts; the separate APK enables a loopback-only test constructor.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/production build/disposable
cp android/app/build/outputs/apk/debug/app-debug.apk build/production/
cp android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk build/production/
cp -R build/bindings build/production/bindings
restore() {
  cp build/production/app-debug.apk android/app/build/outputs/apk/debug/
  cp build/production/app-debug-androidTest.apk android/app/build/outputs/apk/androidTest/debug/
  cp -R build/production/bindings/. build/bindings/
}
trap restore EXIT
RCS_DISPOSABLE_INTEGRATION=1 scripts/generate-bindings.sh
RCS_DISPOSABLE_INTEGRATION=1 scripts/build-android-native.sh
(cd android && ./gradlew --no-daemon -PdisposableIntegration=true lintDebug assembleDebug assembleDebugAndroidTest)
cp android/app/build/outputs/apk/debug/app-debug.apk build/disposable/app-disposable.apk
cp android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk build/disposable/app-disposable-androidTest.apk
# Restore published default paths and bindings to the ordinary build.
restore
python3 - <<'PY'
from pathlib import Path
import subprocess, tempfile, zipfile, os, hashlib
ndk = Path(os.environ['ANDROID_HOME']) / 'ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm'
for kind, apk, enabled in [('production', Path('build/production/app-debug.apk'), False),
                           ('disposable', Path('build/disposable/app-disposable.apk'), True)]:
    with tempfile.TemporaryDirectory(prefix='rcs-symbol-check-') as temporary, zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not name.endswith('/librustclipsync_mobile_ffi.so'): continue
            library = Path(temporary) / 'library.so'
            library.write_bytes(archive.read(name))
            symbols = subprocess.check_output([str(ndk), '--dynamic', '--defined-only', str(library)]).decode()
            assert ('for_disposable_test' in symbols) == enabled, (kind, name)
    print(kind, 'test constructor present:', enabled, 'sha256:', hashlib.sha256(apk.read_bytes()).hexdigest())
PY
