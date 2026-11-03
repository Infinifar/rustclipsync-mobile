# 下一会话入口

先读 AGENTS.md、README-ZH.md、ARCHITECTURE.md，再查看 Git 状态。保持 Kotlin / Compose、
Swift / SwiftUI、同一 Rust facade / UniFFI；协议、RCS1、重试、冲突由 core 负责。
core 固定 6a1ccd37b8ca3e515610d48bfa47f20b7c9b0ca4，通过完整未改动 vendor 独立构建；
统一 /api，不追加 /v1，不依赖相邻仓库。只做移动端，不增加 macOS / Catalyst 目标。

## 当前模型：Android 0.1.4

- 只有一个默认关闭的“后台运行”开关。前后台统一遵守双向 / 仅发送 / 仅接收。
  普通接收文本写入剪贴板，开启后台前确认覆盖风险。不再有第二个后台写入开关。
- 旧版 background-listening 设置不迁移为更宽权限，本版 background-running-v2 默认关闭，
  升级需重新开启。敏感策略写入 sync-settings，界面直接观察 controller，避免副本与会话错位。
- 已标记敏感内容三选一：跳过、加密收件箱、直接写入（不进收件箱）。不能要求“全开”。
  策略持久保存，不重放旧消息。未标记内容不能承诺自动识别敏感。
- 用户明确要求 Shizuku 特权来源。固定 SDK API/provider 13.1.5，显式请求授权；仅在后台
  服务存活、方向含发送、应用离开前台时绑定非 daemon UserService。当前要求无线调试 /
  ADB shell UID 2000；root 模式提示切换，不尝试 su 或冒用 Binder 身份。
- UserService snapshot/observe/destroy 核对实际 Binder UID，Provider 受平台权限保护。
  无账号/密钥/网络/落盘。每秒观察变化，回调只带唤醒信号；锁屏/熄屏不读，RPC 上限
  256 KiB，超限不截断。读失败降级已有事务续传，接收按 core 状态继续。
- facade sync_background(PendingOnly/Privileged) 必须先完成前台 check；串行调用 core
  record/clear/flush/pull_background，不重写重试。仅接收不读、不上传旧候选。
  会话内记住最近观察和成功 OS 写入，防止重复记录相同快照；关闭即释放，不改 RCS1。
- 收件箱页诊断条件、实际策略、最近同步结果和接收过滤。错误策略、无标记、无新消息或
  频道关闭均不会凭空生成卡片；首次注册不重放旧消息。可重显已保存的收件项。
- 显示/隐藏保持通知栏（不走 authenticationRequired）；复制走认证收起路径；私有服务
  仍校验解锁、票据和版本。透明单色图标、按宽度约 2/3 的星号掩码沿用。
- 三页 UI 与设置“同步/过滤/外观/关于”；UUID、core 正则过滤、更新与项目链接沿用。
  中英文 191 个资源键，支持系统语言、中文、English；新增语言走 Android resources。

## 本轮验证与剩余工作

本轮已运行 facade 23 项全特性 Rust 合成测试、fmt、Clippy all-targets/all-features -D warnings、
rustdoc 和隔离 TLS 路由检查；Kotlin/Swift/Python UniFFI 绑定重新生成。详见 VALIDATION.md
及本轮 evidence。新 API 缺失与重复上传的负向测试先失败，修复后通过。
不把 mock / host ABI 当作真实系统剪贴板、Shizuku、Keystore 或设备行为验证。

旧实现 7b9b460 的 Rust CI 41374038027 成功；Android CI 41374038090 已确认在 PrivilegedClipboardService.kt:47 编译失败，不能作为成功构建。
当前已显式声明反射参数 Array<Any?>，升级 core 0.4.0／协议 3，更新 Omaclipsync 图标及白色／黑色背景。当前 Android 编译结果以新 CI 为准，不持续监控。
不要用 0.1.3 的成功代替 0.1.4 平台编译；此前 0.1.3 的 Android run 41341560080 和
Rust run 41341560187 已确认成功。历史 API 31/35 隔离模拟器结果只适用于对应旧构建。

用户明确耗时视觉测试交其执行，设备 moto razr 60 u / Android 16 / MyUI。
不要自动启动模拟器、截图、操作用户真实剪贴板或生产账户；交付 APK 与 ANDROID-REVIEW.md。
本轮 Shizuku 真实授权、Binder 来源、厂商兼容性、通知交互尚待设备验证，不宣称已通过。
iOS 18 为工作基线，只面向 iPhone/iPad；源码、Keychain、SwiftUI 已接入但没有 Xcode 编译。
后续需要 Mac 构建/XCTest；iOS 后台等 APNs 配套，不伪装无限保活。

后续产品任务还有显式重配对、多账户、冲突决策 UI、稳定开发签名与正式发布。不要静默
删状态、自动覆盖远端、用生产凭据做回归，或为签名冲突直接卸载用户数据。

## 构建与远端约定

用户要求默认开发构建使用 GitHub Actions，全部修改后统一构建，不持续 watch/轮询。
代码 push 自动执行 Rust / Android，两端工作流缓存依赖、取消过时 run、保留 APK/报告。
文档修改不触发；GitLab 仅 web/API 手动备用，不恢复双端自动重复构建。
分支 codex/mobile-native-foundation，GitHub origin 私有 Infinifar/rustclipsync-mobile。
GitLab 镜像 Cassius/rustclipsync-mobile 私有项目 ID 9，Runner #3 / android / Docker。
Git SSH gitlab-rocky:20820 登录 @Cassius；管理 shell 为 root@turbo-rocky:22，别混淆。
无需浏览器或新 token；不修改 Runner、v2raya、宿主或共享缓存。

本地新增 JDK/SDK/Gradle 工具此前已按用户要求清理，SDK 许可未接受；不要重新安装来
绕过远端构建偏好。可用现有 Rust toolchain 做共享层检查，临时 target 完成后清理。
完整 check.sh 生成绑定、跑 host ABI、隔离 TLS fixture，不接真实账户；构建步骤见 BUILD.md。
所有报告区分 mock、ABI、平台编译、模拟器和真机。临时服务、容器、证书、数据库用后清理。

## Android 凭据恢复与 Shizuku 授权修复

Android “保存并连接”在连接成功后加密保存凭据。SecureConnectionStore 使用独立
Android Keystore AES-256-GCM key + AtomicFile，noBackupFilesDir/connection；与 core
状态及 wrapping key 分离，不改协议或 vendor。SessionController 异步恢复三个字段，
Activity/语言重建不丢失、不写 SavedState、不自动连接；关闭保留，显式删除只清连接。
读取失败保留文件并阻止覆盖；旧版未保存的内容无法找回，首次升级需连接一次。
ShizukuAccess 将授权生命周期与来源连接分离，匹配授权请求码、处理拒绝和 Binder 死亡，
结果不覆盖身份/版本/权限检查。UI 同时显示授权成功与来源空闲/就绪。
新增 10 项本地 JVM 加密/授权回归测试，4 项 Android 恢复/Keystore instrumentation
只编写交 CI 编译，本轮未启动模拟器或操作真实设备。iOS 凭据行为不变。

### 后台生命周期与接收调度修复

划掉最近任务不再把后台开关永久写成关闭，系统允许时保留已连接的服务；系统超时
只停止本次会话并保留开关，不循环重启。进程被杀、强行停止或重启后仍需前台重新连接。
开启后台运行且连接成功后，App 前台有焦点时也自动接收；前台发送仍使用发送按钮。
服务每个已完成的 tick 后等待 3 秒，网络请求时间另计，不保证固定延迟。
连接结束和窗口重新获得焦点会唤醒接收。Shizuku 授权与服务端连接是独立条件。

遇到旧上传冲突或超过重试期限时，后台同步与重试入口调用 core 的持久化安全跳过：
取消旧上传及当时排队项、保留系统剪贴板、跳过当时远端消息，继续处理后续新消息。
恢复后的复制来源先建立观察基线，避免把保留的旧剪贴板再次作为新上传。
普通网络失败仍保留事务；存储、身份和 epoch 错误不会被伪装成恢复成功。
“有待处理的事务”也可能只是等待确认，不等于已确定冲突；前台接收循环会先处理待办。

## 0.1.5 合并及正式发布

用户实测通过 fe6c031 后，授权补充独立 App 暂停、合并 main 和正式签名发布；
小企鹅单独合并现有 master 并沿用它的签名发布 0.1.5。此前不合并的限制已被此次明确授权覆盖。
独立 App set_paused 使用 core 方向 paused 标记，UI/平台立即阻断，保留配对/收件箱，
暂停偏好持久化。正式签名只用 keytool Keystore，不用 SSH；四项 Actions Secrets 已配置，
私钥备份在仓库外 ~/.local/share/rustclipsync-signing/mobile/。仓库仅提交公开证书及 SHA256。
签名流程在全部检查后自动发布 v0.1.5；不得把进行中的 run 当成已发布。
用户不要求持续 watch，不启动模拟器、不自动安装或卸载设备应用。调试包与首个正式包
签名不同，迁移由用户处理并保留所需内容。

Android 暂停界面控制方法命名为 SessionController.configurePause，不能命名 setPaused：
paused 委托属性的 private setter 已占用 JVM setPaused(Z)V。Rust/UniFFI 方法仍是
MobileSession.setPaused，两个层级不要一并重命名。0.1.5 首次签名构建因此 Kotlin
冲突失败，修复后从新 main 重新启动签名发布；旧的失败 run 不能直接重跑旧源码。
本机缓存 JDK 已按用户要求删除，仅用 /usr/bin/java 和 /usr/bin/keytool（系统 JRE）；
不要重新安装本机 JDK，完整 Android 编译使用 GitHub Actions。

用户授权公开独立 App 仓库、清理该仓库失败 Actions 记录并发布 1.0.0。0.1.5 已成功发布，
1.0.0 沿用其正式证书，versionCode 7。成功构建和原有 Release 保留，失败记录删除前
已记录编译原因；当前 README 默认英文，下载链接指向 v1.0.0。不要重新生成签名密钥。
