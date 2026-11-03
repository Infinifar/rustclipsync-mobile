# RustClipSync Mobile

Independent Kotlin/Jetpack Compose Android and Swift/SwiftUI iOS clients sharing one
Rust/UniFFI facade over a pinned rustclipsync-core revision.
Core **0.4.0** requires **protocol 3 / Server 3.0.0**, using `/api/...`; configure the deployment base URL without
appending `/api` or `/v1`.
The Apple client targets iPhone and iPad only. A macOS client belongs in the desktop repository.

The shared executor, encrypted state integration, binding generation and host smoke tests
are implemented. The current facade passes 23 synthetic Rust tests, strict Clippy, rustdoc and 22 real loopback HTTPS route checks. Android debug/test APK compilation, lint and static 16 KiB alignment
checks passed for earlier builds in remote CI; the current protocol-3 build is verified separately. Ten synthetic tests and an empty Compose screen launch
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


## Protocol 3 and current Android appearance

The core is pinned to release commit `6a1ccd37b8ca3e515610d48bfa47f20b7c9b0ca4`,
with an unchanged, checksummed vendor snapshot. Before data access the facade sets
the selected direction through core and waits for its confirmation. Core owns
WSS control, authenticated HTTP fallback, session fencing and per-device delivery
revisions. Reconnect backfill comes from the server's configured window; it is not
hardcoded in the app. Incompatible peers get a dedicated protocol-version error.
Normal session close preserves pairing and offline eligibility.

Android uses the [Omaclipsync S icon](https://github.com/Infinifar/omaclipsync/blob/main/assets/omaclipsync-icon.svg)
as a bundled adaptive vector, with a monochrome layer for themed launchers. The
system light/dark resource selects a white/black launcher background. App light/dark
modes also use white/black main backgrounds, while retaining Material You accents.
The notification status icon remains its separate transparent monochrome asset.

The previous [Android build](https://github.com/Infinifar/rustclipsync-mobile/actions/runs/41374038090)
failed on mixed-type reflection-array inference in `PrivilegedClipboardService.kt`.
Both argument arrays now declare `Any?` explicitly; caller-UID, lock-screen and
Shizuku ADB-mode checks are retained. Platform compilation is checked by the new
Actions run; no new emulator, Shizuku-device or iOS validation is implied.

## Android saved connections and Shizuku status

**Save and connect** saves the deployment base URL, account and password only after
a successful connection. An independent non-exportable Android Keystore key
encrypts them with AES-256-GCM in an atomic, app-private, no-backup file. Reopening
or recreating the Activity restores the fields with a masked password; reconnecting
still requires a foreground action. Closing a session retains the saved connection.
**Delete saved credentials** removes only that profile, preserving device pairing
and the encrypted inbox; it does not revoke the server token. Read/decryption errors
keep the file intact and require explicit deletion before replacement. An old
build that never saved credentials requires one successful connection after upgrade.
iOS still keeps account passwords in session memory.

Shizuku authorization and clipboard-source readiness are separate: a successful
callback shows **Shizuku access granted**, including while the source is idle.
The source still starts only when the background service is running, sending is
enabled and the app is backgrounded. Denial, unavailable Binder, unsupported root
mode and source errors remain distinct; live identity and permission checks guard
reads. Local JVM regression tests pass; current Android compilation is delegated
to Actions, while actual Keystore, Shizuku and restart behavior need device checks.
