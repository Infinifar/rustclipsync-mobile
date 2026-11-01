# 开发约定

默认使用中文沟通。开始开发前阅读 README-ZH.md、docs/ARCHITECTURE.md 和
docs/NEXT-SESSION.md；以实际代码与验证结果更新文档。

## 范围与架构

- Android 使用 Kotlin / Jetpack Compose，iOS 使用 Swift / SwiftUI。
- 本仓库只开发移动端；Apple 客户端面向 iPhone / iPad，不扩展 macOS 或 Mac Catalyst。
  macOS 客户端之后在桌面端仓库考虑；macOS / Xcode 在此仅作为 iOS 构建宿主。
- 两端调用同一 Rust mobile facade，facade 复用 rustclipsync-core。
- 平台负责剪贴板、Keystore / Keychain、权限和生命周期；Rust facade 负责
  会话串行化、执行线程、关闭管理、接口与错误映射。
- 不在 Kotlin、Swift 或 facade 重写协议、重试、冲突处理或持久化格式。
- HTTP 当前统一使用 /api/...，路由只由 core 维护；服务器配置为部署根地址，不追加 /v1。
- 现有小企鹅输入法维持 Kotlin，本仓库不承担其 Rust 迁移。
- 初始化阶段只有文档；不得声称应用、绑定、跨平台编译或测试已经完成。

## 实施原则

- 改动 Rust 时使用适当的 rust-skills。先读 Cargo.toml、公开 API 和相关模块，
  按实现需要深入；不要一次载入所有客户端源码。
- 优先评估 UniFFI 是否能覆盖同一 facade 的 Kotlin / Swift 绑定；记录结论。
  若需要手写 JNI / C ABI，只添加必要的边界胶水。
- UI 不直接操作 native handle；平台桥接代码集中管理加载、资源与生命周期。
- core 的同步调用和线程等待不得阻塞 UI 主线程；设计时检查跨平台回调死锁。
- 明确关闭入口、幂等性、在途操作与回调撤销；资源回收器不能承担唯一关闭责任。
- panic 不得跨 FFI；错误转换与 panic 策略必须匹配实际构建配置。
- core 依赖固定版本或提交，或使用带来源及校验清单的 vendor；不跟踪浮动 main，
  不提交对开发机器绝对路径的依赖。不直接修改 vendor。

## 隐私与验证

- 不提交凭据、签名密钥、真实剪贴板、配对状态、设备令牌或个人服务器配置。
- 日志、通知、普通状态事件与错误展示不得包含敏感正文或凭据。
- 保留敏感标记；分开处理禁用、加密收件箱和显式自动写入三种策略。
- 不把浏览器扩展复制、密码格式或六位数字视为可靠的敏感标记。
- 回归测试使用合成数据、模拟适配器和隔离服务，不接触用户真实剪贴板或账户。
- Rust 代码实现后运行 fmt、相关测试、Clippy；实际平台编译、模拟器与真机结果
  分别记录，不能用 Linux 编译或 mock 测试替代 iOS / Android 验证。
- SDK 要求、平台后台限制、签名及发布规则在实施时查官方文档，不沿用旧草案中的
  未验证版本、日期、性能数字或 API。
