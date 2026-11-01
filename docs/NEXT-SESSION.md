# 下一会话开发说明

可以直接用下面的说明开始新会话：

> 在 rustclipsync-mobile 仓库继续开发新的独立 Android / iOS 客户端。
> 先读 AGENTS.md、README-ZH.md、docs/ARCHITECTURE.md，并检查仓库当前状态。
> Android 使用 Kotlin / Compose，iOS 使用 Swift / SwiftUI。
> 平台适配层处理剪贴板、Keystore / Keychain、权限与生命周期；两端调用同一个
> Rust mobile facade；facade 负责接口、会话串行化、线程、关闭和错误映射，复用
> rustclipsync-core 的协议、状态机、持久化格式、重试和冲突处理。
> 不重写 core，不迁移现有 Kotlin 输入法。
> 本仓库初始化时只有文档，先完成绑定与线程模型决策，再逐步建立可验证的工程。

## 已有上下文

- core 仓库：https://github.com/Infinifar/rustclipsync-core
- 初始化参考：core `0.2.0`，提交 `a13298c550fa4ebb287e55fe7a229486a36f22f5`。
- core 已有标准错误链与 Worker::shutdown() / join()；Drop 请求停止但不等待。
- 桌面端和 Omaclipsync 使用 core；现有 Android 输入法仍为独立 Kotlin 实现。
- 这里尚未 vendoring、添加依赖或生成绑定，参考提交不代表已完成移动平台交叉编译。
- 本机可能存在相邻 core checkout；它可以帮助阅读源码，但不能成为提交中的绝对路径依赖。

## 开始时先确认

1. Android applicationId、iOS bundle identifier，以及期望的最低系统版本。
2. 首批界面和 Android / iOS 的开发顺序；初始化未指定先做哪端。
3. 当前机器的 Android / Apple 工具链、可用模拟器和真机；明确哪些验证需要其他主机。
4. 共享 facade 的绑定方案、线程 / 回调模型，以及 core 依赖固定方式。
5. 两个平台允许的前后台同步交互；把实际系统限制写进产品行为。

先检查已有工具与项目上下文，再只询问无法推断的产品决策。不要读取真实密钥、保险库、
剪贴板正文或生产账户状态来代替测试数据。

## 建议实施顺序

1. 阅读 core 的 Cargo.toml、公开 API 和适配器契约，按需深入源码。
2. 用最小可编译样例验证同一 facade 的 Kotlin / Swift 绑定、错误与主动关闭。
3. 固定 core 来源，建立 facade 的串行执行与可替换适配器测试。
4. 接入各平台密钥存储、加密状态落盘和剪贴板，再连接原生界面与生命周期。
5. 用隔离服务和合成数据验证与桌面客户端互操作，覆盖敏感策略、断网、重启、冲突与关闭。
6. 为实际可运行的工程建立 CI、构建说明与平台验证记录，再准备签名和发布。

不要把旧 Android 草案中的固定 SDK / NDK 版本、发布日期、Compose API 或 JNI 性能数字
直接写入配置。实施时查阅官方文档，并记录选择和验证结果。
