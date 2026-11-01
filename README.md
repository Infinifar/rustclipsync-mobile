# RustClipSync Mobile

Independent Kotlin/Jetpack Compose Android and Swift/SwiftUI iOS clients sharing one
Rust/UniFFI facade over a pinned rustclipsync-core revision.

The shared executor, encrypted state integration, binding generation and host smoke tests
are implemented. Native platform sources are present; Android/iOS compilation and device
validation remain pending. This is an initial development milestone, not a validated release.

See [中文开发说明](README-ZH.md), [build instructions](docs/BUILD.md),
[binding/lifecycle contract](docs/BINDINGS.md), and [validation evidence](docs/VALIDATION.md).
