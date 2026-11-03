# Android release signing

Android APKs use a Keystore generated with JDK `keytool`, not an OpenSSH key.
The standalone mobile app and the keyboard have independent signing identities.

This repository stores only the public certificate in [android/release-signing.pem](android/release-signing.pem).
Certificate SHA-256: `1badf1febf5285833325c904025f857b5ebd1eef2073bad9aa94c51bb216196e`.

The private Keystore and passwords are never committed. Repository Actions Secrets are
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`.
A private local backup is held outside the repository at
`~/.local/share/rustclipsync-signing/mobile/`; preserve an offline copy of the Keystore and credentials.
GitHub cannot reveal an uploaded secret later. Do not regenerate the release key for an update.

Run **Signed Android release** on `main`. It validates Rust/FFI/HTTPS fixtures,
Android tests/lint and both native ABIs, builds the non-debuggable release APK,
signs it, checks its certificate and 16 KiB alignment, and uploads an artifact.
With `publish_release` enabled, it creates a draft Release, downloads and verifies
its checksums, then publishes it. The signing file is removed from runner temp storage.
Future versions must retain the certificate and increase Android versionCode.

The first signed release is 0.1.5 (versionCode 6). Earlier ephemeral debug signatures
cannot be replaced in place. Follow the migration note in [release notes](releases/0.1.5.md);
no tooling automatically uninstalls a device app or deletes its data.

中文：密钥使用 `keytool` 生成，四项凭据只进入 Actions Secrets，仓库只保留公钥证书。
本机私有备份位于上述仓库外目录，请另做离线备份。后续版本必须沿用本次正式密钥。
小企鹅沿用它原有的独立密钥，不受本仓库配置影响。

Current release: **1.0.0**, Android versionCode **7**. It retains the 0.1.5 certificate for in-place upgrades. Use the installed system JRE keytool locally; Android compilation runs on GitHub Actions, without reinstalling a local JDK.
