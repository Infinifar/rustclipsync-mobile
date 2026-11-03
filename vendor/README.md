# Core source snapshot

rustclipsync-core is copied unchanged from the commit recorded in core-provenance.json.
Its MIT license and all upstream tracked files are retained. Cargo.toml and Cargo.lock
keep the original Git revision; .cargo/config.toml uses Cargo source replacement so a
normal build does not fetch this Git repository. Other locked crates still use crates.io.

Do not edit this source snapshot. Make core changes upstream, validate them there, update
the revision in Cargo.toml and .cargo/config.toml. Temporarily disable the source replacement
while running `cargo update -p rustclipsync-core`, then restore it: Cargo requires the new
Git revision in Cargo.lock before it can resolve that revision from a directory source.
Regenerate the snapshot with Python 3.11+:

```sh
python3 scripts/vendor-core.py
python3 scripts/verify-vendor.py
scripts/check.sh
```

The optional --source-repo argument may use a local Git object store when regenerating;
it is never recorded as a build dependency. Per-file SHA-256 checksums detect accidental
drift; they do not authenticate malicious edits to both files and their checksum manifest.
Review provenance and the upstream commit when changing this snapshot.
