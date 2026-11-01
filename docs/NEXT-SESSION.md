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
- 双端内存适配器绑定 smoke；Android 另有主队列超时撤销与隔离文件存储测试，共 10 项（含通知测试）。
- GitHub Rust / Android APK 开发构建配置；私有 GitLab 镜像与现有 Runner 保留手动备用。

## 用户基线与远端构建

标识均为 io.github.infinifar.rustclipsync。用户确认最低 Android **12 / API 31**，不承担旧 Android
兼容包袱；iOS 依同样意图采用 **iOS 18** 工作基线。首批前台手动发送/接收、状态、加密
敏感收件箱，先 Android 后 iOS。后台监听默认关闭；最新要求允许单独选择后台监听和后台写入，iOS 后续配套推送。未标记文本不可承诺识别敏感。

用户明确 Apple 客户端只做 iPhone / iPad 移动端；macOS 客户端之后在桌面端仓库考虑。
不要在本仓库增加 macOS / Mac Catalyst 目标；Mac / Xcode 要求只涉及 iOS 构建宿主。

用户最新要求将默认开发构建迁到 **GitHub Actions**，减少 Rocky DNS 失败造成的重跑，
并且不持续监控构建。开发分支已推 origin，工作流按代码 push / PR 触发，缓存依赖、
取消同分支过时任务、保留 APK 与报告。GitLab 的 web / API 构建保留手动备用，镜像
push 不自动构建。不要恢复双端自动重复构建。当前可查看 Actions 页面，完成时作一次
状态核对即可，不开启 watch 或轮询循环。文档修改不触发新构建。

此前用户要求使用 **gitlab-rocky 做开发构建，并清理本地新增工具**，已有结果可复用。
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

1. GitLab #131 / #292（7117940）已通过 Rust、Android 两个 ABI、Kotlin、lint、APK 与 16 KiB 静态检查。
   默认构建已迁到 GitHub Actions，首个提交 f9428b1；不要以旧 GitLab 包代表新 GitHub 产物。
   修改共享层或平台代码后按 BUILD.md 重跑；不要以旧 APK 代表新实现。
2. API 31 / 35 的 10 项 smoke 与当前服务 / SystemUI 结果见 VALIDATION.md，复现脚本为
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

## 通知可见性修复（本轮实施）

用户反馈只有后台监听通知。旧实现只在后台收到新 inbox ID 时发卡片，进入前台删除后
不会从已保存消息恢复；系统折叠通知也隐藏动作。已改为按服务 / 策略 / 授权 / 当前收件箱
协调卡片，前台只 mask，加入手动重显、空箱 / 权限状态和 0.1.1 构建号。
新测试 restoredInboxActionsInNotificationShade 先保存并关闭 Native 会话，再重开已有
收件箱，由宿主通过 Android CLI layout 定位实际 SystemUI 控件并 adb 点击；不再只调用
PendingIntent.send 验证动作对象。最终 API 31 / 35 各 10 项 smoke、5 / 6 项完整方法通过，含实际 SystemUI 操作；构建已改 GitHub。

通知卡片使用独立 group key；原生操作在展开后显示。测试脚本只能点准确的
`android:id/expand_button`，不能把 `expandableNotificationRow` 当成展开器，否则会打开应用。
Android CLI / SDK 查询受并发 UiAutomation 所有限制，手工模式可据实际截图操作；失败或
中断不计为通过。构建主机已按最新要求改 GitHub，Rocky 只保留本轮完成的验收产物。

本轮 GitHub Android run 41334613641 与 Rust run 41334613232 均已通过，APK 位于对应 artifacts。
Rocky 最终运行使用 7117940 / CI292，GitHub 编译源 f9428b1 的应用实现代码相同、构建号不同；
GitHub 产物未另行运行模拟器。证据与完整清理记录见 android-notification-ui-2026-11-01。
临时容器、AVD、证书、数据库、ZIP 与本地 actionlint 工具已清理；依赖缓存和 GitHub 构建产物按配置保留。

## 最新工作方式与 Android 0.1.2

用户明确：耗时可视化测试交由用户；不要自动启动模拟器、截图或反复监控。
全部修改完成后统一推 GitHub 构建。设备：moto razr 60 u / Android 16 / MyUI。
显示/隐藏应保持通知栏，复制才收起。依据 Android 16 SystemUI 的
StatusBarRemoteInputCallback.handleRemoteViewClick，appRequestedAuth 分支会关闭 shade；
本轮只关闭 toggle 的 authenticationRequired，服务内解锁、票据、版本校验保留，复制仍 true。
独立透明单色通知图标替代不透明 launcher 图标；三页原生 UI、持久化主题选择及 Material You
明暗配色已实现。协议、core 与 RCS1 不变。真机验收清单见 ANDROID-REVIEW.md。

0.1.2 实现提交 df37e1c；Rust run 41337695078 已通过，Android run 41337695083 在本次
交接记录时构建中。可按用户需要读取最终结果，不开启轮询或恢复可视化测试。不要把
0.1.1 的真机/模拟器结果复用于这次新界面或 MyUI 通知行为。

## 0.1.3 同步规则和语言对齐

用户补充：通知屏蔽字符按宽度约 2/3 自适应、移除副标题、对齐桌面双向/仅发送/仅接收、
文本正则过滤（已明确确认）、设备 UUID、更新检查、项目入口、中英文与后续语言扩展。
Android 已按同步/过滤/外观/关于分区，翻译位于 values 与 values-zh，共 167 个键，
AppLanguage 和 locales_config 为语言登记入口。check-translations.py 校验完整性和格式参数。
facade 新增过滤/方向命令及 UUID 元数据；不要在平台层实现正则或协议。core/vendor 不改。
所有新增 Rust 测试用合成适配器，无实际账户/剪贴板；视觉测试仍交用户。Android 的检查更新
只读公开 Release，私有/未发布不可当作最新；不要嵌入 GitHub 凭据。

0.1.3 最终实现 40dabea，Rust run 41341560187 已通过；Android run 41341560080 在本次
交接记录时仍构建中。无需持续轮询，用户可从同页面下载构建产物。临时 Rust 编译目录和
辅助翻译脚本已删除，保留证据。不能宣称新布局、语言切换或 Moto/MyUI 真机已验收。
