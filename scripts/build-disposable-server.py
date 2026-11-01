#!/usr/bin/env python3
"""Build the pinned upstream server for isolated tests; run on the development builder."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    provenance = json.loads((ROOT / 'tests/disposable-server-source.json').read_text())
    with tempfile.TemporaryDirectory(prefix='rcs-disposable-server-build-') as temporary:
        temporary = Path(temporary)
        archive = temporary / 'source.tar.gz'
        subprocess.run(['curl', '-fsSL', '--retry', '2', '--connect-timeout', '10', '--max-time', '120',
                        provenance['archive_url'], '-o', str(archive)], check=True)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != provenance['archive_sha256']:
            raise SystemExit('Upstream server archive checksum mismatch')
        source = temporary / 'source'
        source.mkdir()
        with tarfile.open(archive) as stream:
            for item in stream:
                path = PurePosixPath(item.name)
                if path.is_absolute() or '..' in path.parts or not (item.isfile() or item.isdir()):
                    raise SystemExit('Unexpected server archive member')
                relative = Path(*path.parts[1:])
                if not relative.parts:
                    continue
                target = source / relative
                if item.isdir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(stream.extractfile(item).read())
        target = ROOT / 'target/server-fixture'
        environment = dict(os.environ, CARGO_TARGET_DIR=str(target))
        subprocess.run(['cargo', 'build', '--locked', '--release'], cwd=source, env=environment, check=True)
        output = ROOT / 'build/disposable-server'
        output.mkdir(parents=True, exist_ok=True)
        binary = output / 'rustclipsync-server'
        shutil.copy2(target / 'release/rustclipsync-server', binary)
        binary.chmod(0o755)
        provenance['binary_sha256'] = hashlib.sha256(binary.read_bytes()).hexdigest()
        (output / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
        print('Built disposable server:', provenance['revision'], provenance['binary_sha256'])


if __name__ == '__main__':
    main()
