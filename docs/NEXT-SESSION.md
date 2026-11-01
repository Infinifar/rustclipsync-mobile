# 下一会话开发说明

开始先依次阅读 AGENTS.md、README-ZH.md、ARCHITECTURE.md、本文件，再看 Git 状态。
不要覆盖尚未提交的实现；本仓库已经超出初始化阶段。按 VALIDATION.md 分开报告
Rust / host FFI、平台编译、模拟器、真实 OS 适配器和真机结果。

## 已完成

- Cargo workspace；core 0.3.0 固定 Git rev 1a8ec6b97cc925fcbbbf51f9b8c8e31f97f1ea4e，锁文件。
  Cargo source replacement 使用该提交完整未改动的 vendor，逐文件校验及来源见 vendor/。
- UniFFI 0.32.2，同一 facade 生成 Kotlin / Swift；额外 Python 绑定仅用于 host ABI smoke。
- 单执行线程 / 16 命令有界队列，非阻塞 requestClose 与重复 await shutdown，存储失败停止。
- core Session 的 check / record_local / flush / pull / 敏感收件箱复用，无协议重写。
- 12 项 Rust synthetic mock 测试、4 项实际 host FFI smoke 通过；fmt / Clippy / rustdoc 通过。
- core 0.3.0 统一 `/api/...`；隔离 TLS 路由 smoke 检查 7 类操作及根路径 / 子路径共 14 个请求。
  升级前已确认旧 core 的 `/v1/devices` 请求使测试失败，升级后通过。
- Android Compose 工程、Keystore 包装 key、RCS1 原子落盘、剪贴板标记、UI 回调撤销。
  #116 / #268（core 0.3.0）完整编译、lint、16 KiB 静态对齐通过；API 31 AVD 6 项合成测试和空界面启动通过。
- 真实服务端 2.0.0 固定 c0e1774；API 31 模拟器完成敏感三策略、断网 latest-only、
  app force-stop、设备 reboot、服务端 restart、丢响应后同 UUID / revision 去重；证据见 VALIDATION。
- retryPending 只委托 core.flush，Android 网络错误后刷新 pending 并提供重试入口。
- SwiftUI 源码、Keychain、原子落盘、剪贴板与 scene 生命周期；XcodeGen / XCFramework 脚本。
- 双端内存适配器绑定 smoke；Android 另有主队列超时撤销与隔离文件存储测试，共 9 项（含通知测试）。
- GitHub Rust CI 与 GitLab Rust / Android 开发构建配置；私有镜像与现有 Runner 已实际接入。

## 用户基线与远端构建

标识均为 io.github.infinifar.rustclipsync。用户确认最低 Android **12 / API 31**，不承担旧 Android
兼容包袱；iOS 依同样意图采用 **iOS 18** 工作基线。首批前台手动发送/接收、状态、加密
敏感收件箱，先 Android 后 iOS。后台监听默认关闭；最新要求允许单独选择后台监听和后台写入，iOS 后续配套推送。未标记文本不可承诺识别敏感。

用户明确 Apple 客户端只做 iPhone / iPad 移动端；macOS 客户端之后在桌面端仓库考虑。
不要在本仓库增加 macOS / Mac Catalyst 目标；Mac / Xcode 要求只涉及 iOS 构建宿主。

用户要求使用 **gitlab-rocky 做开发构建，并清理本地新增工具**。
Git SSH gitlab-rocky:20820 认证为 @Cassius；同机管理使用 root@turbo-rocky:22 与现有密钥，
无需浏览器或新建 API token。不要混淆两种 SSH 服务。
本次尝试安装的本地 JDK / SDK tools / Gradle 和缓存已清理，本机 SDK 许可未接受。
不能重新在本机安装来绕过这项偏好。

用户已确认创建 **Cassius/rustclipsync-mobile 私有开发镜像** 并使用现有 Runner。
镜像已创建，项目 ID 9，GitHub origin 保持不变；gitlab remote 指向 gitlab-rocky。
已确认现有实例 Runner #3，标签 android、Docker executor；默认镜像缺失，项目显式固定
Cirrus SDK 36-ndk digest，在临时任务容器补齐 JDK 17 / Rust 1.99.1 / NDK 28.2。
新镜像已关闭 Auto DevOps，仅运行仓库声明的开发检查与调试 APK，不修改 Runner 配置。
远端构建结果、具体 job 和产物见 VALIDATION.md。

## 紧接着做

1. 最新 GitLab #125 / #284 已完整通过 Rust、Android 两个 ABI、Kotlin、lint、APK 与 16 KiB 静态检查。
   修改共享层或平台代码后按 BUILD.md 重跑；不要以旧 APK 代表新实现。
2. API 31 / 35 的 9 项 smoke 与 4 / 5 项服务测试结果见 VALIDATION.md，复现脚本为
   scripts/test-android-service.py --api 31 或 --api 35。不把内存适配器通过当成真实剪贴板 / Keystore 验证。
3. 在 Mac 选择实际 Xcode，执行 build-ios-native.sh、XcodeGen、xcodebuild 和 XCTest。
   Swift 源码未编译，不要把生成 Swift 绑定与 iOS 编译混淆。
4. scripts/test-android-service.py 已完成 Android 绑定与真实隔离服务互操作、断网及重启。
   剪贴板为 mock、文件独立，真实 OS 剪贴板 / Keystore、完整连接表单点击 / 真机仍需独立环境；服务和通知生命周期已在模拟器验证。
   桌面客户端端到端与冲突 UI 仍待验证。暂不接生产、用户剪贴板或现有配对配置。
5. 后续产品补齐显式重配对/多账户与冲突决策 UI，不能默默删除状态或自动覆盖远端。

修改共享代码后运行 scripts/check.sh。平台生成文件不用手改；保持原协议与 RCS1。
详见 BINDINGS.md 的关闭、回调、取消和存储契约。所有验证证据写入 VALIDATION.md，
分别标注 mock、真实 ABI 调用、平台编译、模拟器和真机。

测试包 feature disposable-tests / appId .disposable 只用于回环 TLS fixture，普通包不含测试构造符号。
临时服务与模拟器环境本轮已清理；重跑需新容器，不恢复旧合成状态。iOS 重试 UI 尚未接入。

## 本轮后台功能交接（已验证）

Android 实现独立默认关闭的后台监听和后台写入开关，前台服务只由前台已连接会话启动。
新增 BackgroundSyncService、InboxNotifications；加密收件箱
通知默认 **，两个相邻操作为显示 / 隐藏和复制。前台、熄屏、消息替换、权限变化与关闭
使预览失效，旧 PendingIntent 主动取消。复制操作在后台直接写入完整敏感正文，使用仅对该消息有效的临时授权，仍不读取剪贴板。
core 的 pull_background / pull_inbox 复用原来的接收路径，后台不读剪贴板；写入需确认，
不保证保留其他应用刚复制的值。协议 / RCS1 不变，无后台上传重试。

core 分支 codex/mobile-background-inbox 含两个本地提交，最后为上述固定 SHA，尚未推公共
上游。mobile 完整 vendor 经过逐文件校验，构建不需相邻仓库或取得该未公开 Git 对象。
本地 core 49 项、facade 12 项测试和 fmt / Clippy 通过。最终 GitLab #125 / job #284（fb4bcaf）通过编译与 lint；API 31 / 35 各 9 项 smoke，
服务方法分别 4 / 5 项通过，API 35 含真实系统超时分支，详见 VALIDATION.md。

CI 源码获取前的 DNS 回退仅作用于临时 Docker helper，prepare-android.sh 对临时 build
容器执行相同检查；宿主、Runner、v2raya 未改。测试脚本支持 --api 31/35/36，输出位于
build/service-e2e-apiN。9 项普通合成 smoke，真实服务完整方法数 API 31 为 4、API 35+ 为 5，
另有故障注入中断准备阶段，不计为通过。通知 cancel 为异步，测试必须有上限地等待系统
移除完成，不能直接断言即时消失。临时容器、AVD、证书、数据库、ZIP 与本地 mobile target
均已清理；共享 Runner / 镜像 / v2raya 保留。
