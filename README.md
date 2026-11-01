# RustClipSync Mobile

Native Android and iOS clients sharing one Rust mobile facade and the
[rustclipsync-core](https://github.com/Infinifar/rustclipsync-core) synchronization engine.

[简体中文](README-ZH.md)

## Status

Repository initialization and architecture handoff only. There is no runnable
application, FFI implementation, Cargo workspace, or platform build yet.

## Architecture

```text
Android: Kotlin / Compose + platform adapters ─┐
                                             ├─ shared Rust mobile facade ─ rustclipsync-core
iOS: Swift / SwiftUI + platform adapters ──────┘
```

| Layer | Owns |
| --- | --- |
| Platform adapters | Clipboard access, Keystore / Keychain integration, permissions, app lifecycle and native UI |
| Shared Rust facade | Mobile interfaces, serialized session operations, thread ownership, shutdown and error mapping |
| rustclipsync-core | Protocol, synchronization state machine, encrypted persistence format, retries and conflict handling |

Read [AGENTS.md](AGENTS.md), [architecture](docs/ARCHITECTURE.md), and the
[next-session handoff](docs/NEXT-SESSION.md) before implementing either app.
The existing Kotlin input method remains a separate project.

## Dependencies and builds

Core reference at initialization: version `0.2.0`, commit
`a13298c550fa4ebb287e55fe7a229486a36f22f5`.
This reference is recorded for planning; no dependency has been installed or vendored.
The first implementation must pin and verify its core source so a clean clone
can build without adjacent local repositories.

Build instructions and CI will be added alongside executable code. SDK versions,
minimum OS versions, app identifiers and FFI tooling remain to be decided.

MIT licensed; see [LICENSE](LICENSE).
