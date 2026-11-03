"""Detect vendor drift; commit provenance is reviewed separately, not authenticated by checksums."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / 'vendor' / 'rustclipsync-core'
provenance = json.loads((root / 'vendor' / 'core-provenance.json').read_text())
workspace = (root / 'Cargo.toml').read_text()
replacement = (root / '.cargo' / 'config.toml').read_text()
for text in [workspace, replacement]:
    if provenance['source_url'] not in text or provenance['revision'] not in text:
        raise SystemExit('Core dependency and vendor provenance disagree')
checksum = json.loads((source / '.cargo-checksum.json').read_text())
actual = {str(file.relative_to(source)): hashlib.sha256(file.read_bytes()).hexdigest()
          for file in source.rglob('*') if file.is_file() and file.name != '.cargo-checksum.json'}
if actual != checksum['files'] or len(actual) != provenance['upstream_file_count']:
    raise SystemExit('Vendored core changed; regenerate from the verified upstream commit')
print('Unchanged core vendor files verified:', len(actual), provenance['revision'])
