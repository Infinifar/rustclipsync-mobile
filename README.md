# RustClipSync Mobile

Independent Kotlin/Jetpack Compose Android and Swift/SwiftUI iOS clients sharing one
Rust/UniFFI facade over a pinned rustclipsync-core revision.
The core uses the current `/api/...` endpoints; configure the deployment base URL without
appending `/api` or `/v1`.
The Apple client targets iPhone and iPad only. A macOS client belongs in the desktop repository.

The shared executor, encrypted state integration, binding generation and host smoke tests
are implemented. Android debug/test APK compilation, lint and static 16 KiB alignment
checks pass in private GitLab CI. Six synthetic tests and an empty Compose screen launch
pass on an isolated Android 12 emulator. Real-server integration also covers send/receive,
sensitive policies, offline recovery and process/device/server restarts with a synthetic
clipboard and isolated encrypted files. iOS compilation, real OS adapter behavior and
physical-device validation remain pending.

See [中文开发说明](README-ZH.md), [build instructions](docs/BUILD.md),
[binding/lifecycle contract](docs/BINDINGS.md), and [validation evidence](docs/VALIDATION.md).


Android adds separate, default-off **background listening** and **background clipboard
writes** switches. Listening uses a user-started foreground service; background receives
never read the clipboard. Writes require an additional explicit confirmation and obey the
existing sensitive policy. With writes off, ordinary messages await foreground handling.

With encrypted inbox enabled, the app in the background, its listener running, and
notifications/channel authorized, a newly received inbox item creates a notification
masked as `**`. Its adjacent text actions are **Show / Hide** (one toggling button) and
**Copy**. Showing requires unlocking; copying opens a private Activity and waits for focus.
New messages, screen-off, foreground entry, policy changes and shutdown reset the preview.
Notification denial does not prevent encrypted storage. The server retains only the latest
message. Background writes can overwrite another app's recent clipboard value because
background reads are unavailable. Unmarked text cannot reliably be classified as sensitive.

Android background execution is bounded by system foreground-service limits; it is not
an always-on guarantee. iOS remains foreground-only until push support is added. See
[behavior, platform limits and sources](docs/BACKGROUND.md) and [validation](docs/VALIDATION.md).
