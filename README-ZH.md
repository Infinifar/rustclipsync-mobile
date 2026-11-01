# RustClipSync Mobile

新的独立 Android 与 iOS 客户端，共享同一移动端 Rust facade 和
[rustclipsync-core](https://github.com/Infinifar/rustclipsync-core) 同步引擎。

当前只完成仓库初始化和架构交接，尚无可运行应用、Cargo workspace、FFI 绑定或平台构建。
现有小企鹅输入法继续保留 Kotlin 实现，不在本仓库迁移。

## 已确定的分工

| 层 | 职责 |
| --- | --- |
| Android：Kotlin / Compose | 原生界面、剪贴板、Keystore、权限与生命周期适配 |
| iOS：Swift / SwiftUI | 原生界面、剪贴板、Keychain、权限与生命周期适配 |
| 共享 Rust facade | 移动端接口、会话操作串行化、线程与关闭管理、错误映射 |
| rustclipsync-core | 协议、同步状态机、持久化格式、重试与冲突处理 |

平台 UI → 平台适配层 → 同一移动端 Rust facade → rustclipsync-core。
OS 密钥存储和磁盘原子写入属于适配工作；复用 core 的格式与事务规则。

## 后续会话入口

1. 阅读 [AGENTS.md](AGENTS.md)。
2. 阅读 [架构与边界](docs/ARCHITECTURE.md)。
3. 使用 [下一会话开发说明](docs/NEXT-SESSION.md) 开始实施。

初始化参考 core `0.2.0`，提交
`a13298c550fa4ebb287e55fe7a229486a36f22f5`，尚未添加依赖。
实际开发时固定来源并验证，不能依赖开发机器上相邻目录才能构建。

应用标识、最低系统版本、SDK / NDK / Xcode 版本及绑定工具尚未定稿。
后续实施需核对当时的官方文档，再写入可复现的构建配置和 CI。

许可证：[MIT](LICENSE)。
