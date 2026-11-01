"""Regenerate an unchanged core snapshot from the workspace's pinned Git revision (Python 3.11+)."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import tempfile
import tomllib

root = Path(__file__).resolve().parents[1]
config = tomllib.loads((root / 'Cargo.toml').read_text())['workspace']['dependencies']['rustclipsync-core']
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source-repo', type=Path, help='Optional existing Git object store for maintenance; never a build dependency')
args = parser.parse_args()
revision = config['rev']
if len(revision) != 40 or any(character not in '0123456789abcdef' for character in revision):
    raise SystemExit('Core must be pinned to a full Git commit')
with tempfile.TemporaryDirectory(prefix='rcs-core-vendor-') as temporary:
    temporary = Path(temporary)
    source = args.source_repo
    if source is None:
        source = temporary / 'upstream'
        subprocess.run(['git', 'init', '--quiet', str(source)], check=True)
        subprocess.run(['git', '-C', str(source), 'fetch', '--depth=1', config['git'], revision], check=True)
    def git(*arguments):
        return subprocess.check_output(['git', '-C', str(source), *arguments])
    if git('rev-parse', revision + '^{commit}').decode().strip() != revision:
        raise SystemExit('Upstream revision mismatch')
    archive = git('archive', '--format=tar', revision)
    staged = temporary / 'rustclipsync-core'
    staged.mkdir()
    with tarfile.open(fileobj=io.BytesIO(archive)) as stream:
        for entry in stream:
            path = PurePosixPath(entry.name)
            if path.is_absolute() or '..' in path.parts or not (entry.isdir() or entry.isfile()):
                raise SystemExit('Unexpected upstream archive entry')
            destination = staged / entry.name
            if entry.isdir():
                destination.mkdir(parents=True, exist_ok=True)
            else:
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(stream.extractfile(entry).read())
                os.chmod(destination, entry.mode & 0o777)
    files = {str(file.relative_to(staged)): hashlib.sha256(file.read_bytes()).hexdigest()
             for file in sorted(staged.rglob('*')) if file.is_file()}
    (staged / '.cargo-checksum.json').write_text(json.dumps({'files': files, 'package': None}, indent=2, sort_keys=True) + '\n')
    provenance = {'source_url': config['git'], 'revision': revision,
                  'upstream_tree': git('rev-parse', revision + '^{tree}').decode().strip(),
                  'git_archive_sha256': hashlib.sha256(archive).hexdigest(),
                  'upstream_file_count': len(files), 'license': 'MIT'}
    destination = root / 'vendor' / 'rustclipsync-core'
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(staged, destination)
    (root / 'vendor' / 'core-provenance.json').write_text(json.dumps(provenance, indent=2, sort_keys=True) + '\n')
    print('Exported unchanged core commit:', revision, '; files:', len(files))
