# RustClipSync Mobile

Independent Kotlin/Jetpack Compose Android and Swift/SwiftUI iOS clients sharing one
Rust/UniFFI facade over a pinned rustclipsync-core revision.
The core uses the current `/api/...` endpoints; configure the deployment base URL without
appending `/api` or `/v1`.
The Apple client targets iPhone and iPad only. A macOS client belongs in the desktop repository.

The shared executor, encrypted state integration, binding generation and host smoke tests
are implemented. Android debug/test APK compilation, lint and static 16 KiB alignment
checks pass in remote CI. Ten synthetic tests and an empty Compose screen launch
pass on isolated Android 12 and Android 15 emulators. Real-server integration also covers send/receive,
sensitive policies, offline recovery and process/device/server restarts with a synthetic
clipboard and isolated encrypted files. iOS compilation, real OS adapter behavior and
physical-device validation remain pending. Notification restoration and actual SystemUI
Show/Hide/Copy are covered by the current isolated-server tests; see the validation matrix
for exact APKs, complete runs and incomplete diagnostics.

Development builds now run in [GitHub Actions](https://github.com/Infinifar/rustclipsync-mobile/actions).
The workflows cache dependencies, cancel superseded builds, and retain APKs/checksums/reports.
The private GitLab mirror remains a manually started fallback.

See [中文开发说明](README-ZH.md), [build instructions](docs/BUILD.md),
[binding/lifecycle contract](docs/BINDINGS.md), and [validation evidence](docs/VALIDATION.md).


Android adds separate, default-off **background listening** and **automatic background clipboard
writes** switches. Listening uses a user-started foreground service; background receives
never read the clipboard. Writes require an additional explicit confirmation and obey the
existing sensitive policy. With writes off, ordinary messages await foreground handling.

With encrypted inbox enabled, its listener running, notifications/channel authorized,
and a saved inbox item available, the app creates or restores a notification
masked as `**`. Its adjacent text actions are **Show / Hide** (one toggling button) and
**Copy**. Showing requires unlocking; copying explicitly writes the complete marked item in the background, independently of automatic writes.
New messages, screen-off, foreground entry, policy changes and shutdown reset the preview.
Notification denial does not prevent encrypted storage. The server retains only the latest
message. Background writes can overwrite another app's recent clipboard value because
background reads are unavailable. Unmarked text cannot reliably be classified as sensitive.

Android background execution is bounded by system foreground-service limits; it is not
an always-on guarantee. iOS remains foreground-only until push support is added. See
[behavior, platform limits and sources](docs/BACKGROUND.md) and [validation](docs/VALIDATION.md).

Version 0.1.1 displays its build revision in the app. Saved inbox notifications survive
foreground entry with their body masked; the app provides **Show inbox notification**
to restore a dismissed card and explains an empty inbox or blocked channel. Native
text actions appear after expanding the notification.

Android 0.1.2 separates Sync, Inbox, and Settings. Appearance supports persistent
system/light/dark selection with Material You colors. A transparent monochrome status
icon replaces the launcher image in notifications. Show/Hide no longer request the
system authentication flow that closes the shade; service-side unlock checks remain.
Copy retains that flow. MyUI / Android 16 visual validation is delegated to the user;
see [device checklist](docs/ANDROID-REVIEW.md).
