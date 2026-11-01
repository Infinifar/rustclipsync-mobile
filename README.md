# RustClipSync Mobile

Independent Kotlin/Jetpack Compose Android and Swift/SwiftUI iOS clients sharing one
Rust/UniFFI facade over a pinned rustclipsync-core revision.
The Apple client targets iPhone and iPad only. A macOS client belongs in the desktop repository.

The shared executor, encrypted state integration, binding generation and host smoke tests
are implemented. Android debug/test APK compilation, lint and static 16 KiB alignment
checks pass in private GitLab CI. Six synthetic tests and an empty Compose screen launch
pass on an isolated Android 12 emulator. iOS compilation, real OS adapter behavior and
physical-device validation remain pending.

See [中文开发说明](README-ZH.md), [build instructions](docs/BUILD.md),
[binding/lifecycle contract](docs/BINDINGS.md), and [validation evidence](docs/VALIDATION.md).
