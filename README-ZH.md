# RustClipSync Mobile

独立 Android（Kotlin / Jetpack Compose）与 iOS（Swift / SwiftUI）客户端，
共用 Rust mobile facade 和 [rustclipsync-core](https://github.com/Infinifar/rustclipsync-core)。
现有小企鹅输入法维持 Kotlin，本仓库不迁移其实现。

已建立 Cargo workspace、UniFFI 双端绑定生成、串行会话和主动关闭，并接入两端原生界面、
剪贴板与安全存储适配源码。Rust mock 与实际 host FFI smoke 已通过；**Android / iOS
平台编译和真机验证尚未完成**。详细证据见 [验证记录](docs/VALIDATION.md)。

| 层 | 职责 |
| --- | --- |
| Android 12+ / Compose | 界面、系统剪贴板、Keystore、权限与生命周期 |
| iOS 18+ / SwiftUI | 界面、系统剪贴板、Keychain、权限与生命周期 |
| 同一 Rust facade | UniFFI 接口、串行执行线程、错误映射、幂等关闭 |
| core 0.2.0 | 协议、同步状态机、RCS1 格式、重试、确认与冲突 |

core 固定到 Git 提交 `a13298c550fa4ebb287e55fe7a229486a36f22f5`，Cargo.lock 固定传递依赖，
不依赖相邻目录。首版为前台手动发送/接收；敏感默认跳过，另有加密收件箱和显式自动写入。
未标记文本无法保证识别为敏感内容。账户密码仅保存在会话内存，离开前台关闭会话。

```sh
scripts/check.sh
```

运行需要 Rustup、C 编译器、Git 和 Python 3。Android 按要求使用 GitLab 开发构建；
iOS 需要 macOS / Xcode，Linux 不提供 iOS 编译验证。

- [构建与开发](docs/BUILD.md)
- [共享绑定、线程与关闭契约](docs/BINDINGS.md)
- [架构与边界](docs/ARCHITECTURE.md)
- [验证记录](docs/VALIDATION.md)
- [下一会话入口](docs/NEXT-SESSION.md)

许可证：[MIT](LICENSE)。
