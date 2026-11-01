"""Check every bundled native library, including JNA, for 16 KiB ELF alignment."""
from pathlib import Path
import subprocess
import sys
import zipfile

apk, readelf = sys.argv[1:]
output = Path("build/apk-inspect")
output.mkdir(parents=True, exist_ok=True)
found = set()
with zipfile.ZipFile(apk) as archive:
    for name in archive.namelist():
        if not name.startswith("lib/") or not name.endswith(".so"):
            continue
        abi = name.split("/")[1]
        if abi not in {"arm64-v8a", "x86_64"}:
            raise SystemExit(f"Unexpected APK ABI: {abi}")
        destination = output / (abi + "-" + Path(name).name)
        destination.write_bytes(archive.read(name))
        report = subprocess.check_output([readelf, "-lW", str(destination)], text=True)
        segments = [line for line in report.splitlines() if line.strip().startswith("LOAD ")]
        if not segments or any(int(line.split()[-1], 16) < 16384 for line in segments):
            raise SystemExit(f"Not 16 KiB aligned: {name}")
        found.add((abi, Path(name).name))
        print(f"16 KiB ELF alignment passed: {name}")
for abi in ["arm64-v8a", "x86_64"]:
    for library in ["librustclipsync_mobile_ffi.so", "libjnidispatch.so"]:
        if (abi, library) not in found:
            raise SystemExit(f"Missing native dependency: {abi}/{library}")
