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


Android 0.1.4 uses one default-off **Run in background** switch. **Two-way / Send only /
Receive only** controls both foreground and background operation. Incoming ordinary text
writes the clipboard and may replace another app's recent copy. Upgrade users must re-enable
the expanded background capability; there is no second background write switch.

Install and start **Shizuku 13+ in wireless debugging / ADB shell mode**, then select **Request Shizuku access** to capture new
background copies. The source binds only while the app is backgrounded, background operation
is active and the direction includes sending. It does not read while locked or screen-off.
Without access, already-owned uploads and receiving continue. Oversized RPC text (over 256 KiB)
is rejected without truncation. Shizuku does not remove Android service time limits.

Sensitive content is one persistent, exclusive choice: **Skip marked sensitive messages**,
**Save in encrypted inbox**, or **Write directly to clipboard (no inbox)**. Unmarked text
cannot reliably be identified as sensitive. With receiving enabled, the encrypted inbox policy,
a running background service, notification/channel authorization and an eligible saved item,
a separate inbox notification offers **Show / Hide** and **Copy**. The default star mask adapts
to screen width independently of content length. Show/Hide keeps the shade open; Copy uses
the system closing/authentication path. MyUI behavior remains user-owned device validation.

The inbox page explains unmet conditions, the active policy, incoming filtering and the latest
sync result. Initial registration does not replay old messages; direct writes create no new
inbox notification. Stop, screen-off and lifecycle changes revoke previews and old actions.
Android execution is bounded by foreground-service limits. iOS remains foreground-only pending
APNs support. See [behavior and official sources](docs/BACKGROUND.md) and [validation](docs/VALIDATION.md).

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

Android 0.1.3 adds desktop-style two-way/send-only/receive-only directions, core-backed
regex blocklist/allowlist rules and synthetic sample testing, plus the durable device UUID.
Settings separates sync, filters, appearance and about. Follow-system, Simplified Chinese
and English cover UI, status/errors and notifications through extensible Android resources.
The notification mask adapts to display width; About links to the project and checks public
release metadata without using sync credentials. Private/unpublished releases are reported
as unavailable rather than up to date. Device and visual checks remain user-owned.
