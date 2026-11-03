# RustClipSync Mobile

English | [简体中文](README-ZH.md)

Standalone Android clipboard sync with a Kotlin / Jetpack Compose UI and a shared
Rust/UniFFI engine over [rustclipsync-core](https://github.com/Infinifar/rustclipsync-core).
The Swift/SwiftUI iPhone/iPad client is in development; this release contains Android only.

[Download Android 0.1.5](https://github.com/Infinifar/rustclipsync-mobile/releases/tag/v0.1.5)
· [Release notes](releases/0.1.5.md)
· [RustClipSync Server](https://github.com/Infinifar/rustclipsync-server)
· [Desktop client](https://github.com/Infinifar/rustclipsync-desktop)
· [Omaclipsync](https://github.com/Infinifar/omaclipsync)

## Features

- Two-way, send-only and receive-only directions, reported to the server before data access.
- **Pause / Resume** on the Sync page: stops both directions, invalidates in-flight clipboard operations, preserves pairing and the encrypted inbox, and stays paused after reopening. Failed pause reports still block local access; resume waits for confirmation.
- Encrypted saved connection credentials protected by an independent Android Keystore key.
- Optional background operation, including automatic receiving while the app is open. New background copies require **Shizuku 13+ started through wireless debugging / ADB shell mode**; receiving does not require Shizuku.
- Three exclusive policies for marked sensitive content: skip, encrypted inbox, or explicitly enabled clipboard auto-write. Inbox notifications are masked by default, with unlocked Show/Hide and Copy actions.
- Shared core regex filters, safe recovery of stale uploads, English/Chinese and system/light/dark themes. Launcher uses the Omaclipsync S icon and white/black backgrounds.

## Connect and sync

Android **12+**; the signed APK contains **ARM64 and x86_64**. Requires **Server 3.0.0 / protocol 3** and pinned core **0.4.0**. Enter the HTTPS deployment base URL without appending `/api` or `/v1`, then select **Save and connect**. Initial pairing does not replay old messages.

Choose direction and sensitive policy in Settings. Enable Run in background to receive automatically; ordinary incoming messages write the system clipboard, while marked sensitive messages obey the selected policy. Foreground sending remains manual. Pause disables both automatic and manual transfers; explicit copying from the existing inbox remains available. Pause discards unsent local candidates through core; committed requests cannot be recalled.

Removing the recent task retains the background preference and attempts to keep the live service. Android/OEM service limits still apply. Process death, force-stop or reboot requires foreground reconnection; neither saved credentials nor Shizuku authorization starts a connection on its own.

Run one sync client per Android clipboard. If using the standalone app, disable the separate RustClipSync module in the Fcitx5 keyboard to avoid competing writers.

## Privacy and signing

Unmarked text cannot reliably be classified as sensitive. In particular, the [checked Bitwarden browser clipboard implementation](https://github.com/bitwarden/clients/blob/main/apps/browser/src/platform/services/browser-clipboard.service.ts) copies ordinary text without native sensitivity flags; password-shaped text is not used as a substitute for source metadata. A hidden Android clipboard preview is not proof that an encrypted inbox received the item.

Connection credentials and core RCS1 state are stored encrypted in separate no-backup files. HTTPS protects transport; this is not end-to-end encryption. Do not publish credentials, pairing tokens, state keys or clipboard bodies.

**First signed release:** earlier debug APKs have a different certificate under the same package name and cannot be overwritten. See [migration instructions](releases/0.1.5.md). Subsequent official updates use the same certificate. Public certificate and CI process: [SIGNING.md](SIGNING.md).

## Development and validation

[Build](docs/BUILD.md) · [Architecture](docs/ARCHITECTURE.md) · [Bindings](docs/BINDINGS.md) · [Background behavior](docs/BACKGROUND.md) · [Validation](docs/VALIDATION.md) · [Device checklist](docs/ANDROID-REVIEW.md)

GitHub Actions validates shared Rust/FFI code, protocol fixtures, Android compilation/lint and signed release artifacts. User device validation covers the existing background model; the new pause control has synthetic regression coverage, with device behavior to be verified separately. iOS compilation/runtime validation remains pending.

License: [MIT](LICENSE).
