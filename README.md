# RustClipSync Mobile

English | [简体中文](README-ZH.md)

Standalone Android clipboard sync with a Kotlin / Jetpack Compose UI and a shared
Rust/UniFFI engine over [rustclipsync-core](https://github.com/Infinifar/rustclipsync-core).
The Swift/SwiftUI iPhone/iPad client is in development; this release contains Android only.

[Download Android 0.2.0](https://github.com/Infinifar/rustclipsync-mobile/releases/tag/v0.2.0)
· [Release notes](releases/0.2.0.md)
· [RustClipSync Server](https://github.com/Infinifar/rustclipsync-server)
· [Desktop client](https://github.com/Infinifar/rustclipsync-desktop)
· [Omaclipsync](https://github.com/Infinifar/omaclipsync)

## Features

- Two-way, send-only and receive-only directions, reported to the server before data access.
- **Pause / Resume** on the Sync page: stops both directions, invalidates in-flight clipboard operations, preserves pairing and the encrypted inbox, and stays paused after reopening. Failed pause reports still block local access; resume waits for confirmation.
- Encrypted saved connection credentials protected by an independent Android Keystore key.
- Optional background operation, including automatic receiving while the app is open. New background copies require **Porter 0.7.0-rc0+ or Shizuku 13+ in wireless debugging / ADB shell mode**. Porter takes priority when both are installed and works directly without its compatibility companion. Receiving works without this access.
- Three exclusive policies for marked sensitive content: skip, encrypted inbox, or explicitly enabled clipboard auto-write. Inbox notifications are masked by default, with unlocked Show/Hide and Copy actions.
- Shared core regex filters, safe recovery of stale uploads, English/Chinese and system/light/dark themes. Launcher uses the Omaclipsync S icon and white/black backgrounds.

## Connect and sync

Android **12+**; the signed APK contains **ARM64 and x86_64**. Requires **Server 3.0.0 / protocol 3** and pinned core **0.4.0**. Enter the HTTPS deployment base URL without appending `/api` or `/v1`, then select **Save and connect**. Initial pairing does not replay old messages.

Choose direction and sensitive policy in Settings. Enable Run in background to receive automatically; ordinary incoming messages write the system clipboard, while marked sensitive messages obey the selected policy. Foreground sending remains manual. Pause disables both automatic and manual transfers; explicit copying from the existing inbox remains available. Pause discards unsent local candidates through core; committed requests cannot be recalled.

Removing the recent task retains the background preference and attempts to keep the live service. Android/OEM service limits still apply. Process death, force-stop or reboot requires foreground reconnection; neither saved credentials nor clipboard source authorization starts a connection on its own.

Run one sync client per Android clipboard. If using the standalone app, disable the separate RustClipSync module in the Fcitx5 keyboard to avoid competing writers.

## Precautions: other clipboard sync tools

**Use one clipboard synchronization system for the same group of devices.** Disable overlapping clipboard sharing in other tools, such as Lenovo Smart Connect / Super Connect, Windows cloud clipboard, phone-link utilities, KDE Connect or remote-desktop clipboard redirection. Their other features can remain enabled. Using one RustClipSync client on each of several devices is supported; avoid running multiple sync clients on the same device.

Another tool may strip or replace RustClipSync's clipboard origin marker when forwarding text. A client can then treat the returned text as a new local copy, assign a new message ID and upload it again, creating a feedback loop with repeated receiving, sending and notifications. Message-ID and cursor deduplication cannot stop copies that have been assigned new IDs; cross-tool loop prevention is not guaranteed.

If a loop occurs, pause synchronization on the affected clients, disable the overlapping clipboard-sharing channel, then resume the clients one at a time and verify with a single non-sensitive test copy. Use the sync pause/disable control; closing a window may leave the background client running.

## Privacy and signing

Unmarked text cannot reliably be classified as sensitive. In particular, the [checked Bitwarden browser clipboard implementation](https://github.com/bitwarden/clients/blob/main/apps/browser/src/platform/services/browser-clipboard.service.ts) copies ordinary text without native sensitivity flags; password-shaped text is not used as a substitute for source metadata. A hidden Android clipboard preview is not proof that an encrypted inbox received the item.

Connection credentials and core RCS1 state are stored encrypted in separate no-backup files. HTTPS protects transport; this is not end-to-end encryption. Do not publish credentials, pairing tokens, state keys or clipboard bodies.

**Upgrading:** version 0.2.0 uses the same release certificate as 0.1.5 and supports in-place upgrades. Earlier temporary debug APKs have a different certificate under the same package name and cannot be overwritten. See [migration instructions](releases/0.2.0.md). Subsequent official updates use the same certificate. Public certificate and CI process: [SIGNING.md](SIGNING.md).

## Development and validation

[Build](docs/BUILD.md) · [Architecture](docs/ARCHITECTURE.md) · [Bindings](docs/BINDINGS.md) · [Background behavior](docs/BACKGROUND.md) · [Validation](docs/VALIDATION.md) · [Device checklist](docs/ANDROID-REVIEW.md)

GitHub Actions validates shared Rust/FFI code, protocol fixtures, Android compilation/lint and signed release artifacts. User device validation covers the existing background model; the new pause control has synthetic regression coverage, with device behavior to be verified separately. iOS compilation/runtime validation remains pending.

License: [MIT](LICENSE).
