# 验证记录

最新协议 3／core 0.4.0 结果见本页末尾的 2026-11-03 记录。下表保留 2026-11-01 的历史构建证据，不能代表当前 APK。

日期：2026-11-01。所有测试使用合成数据；没有读取真实剪贴板、账户、配对状态或密钥。

| 层次 | 结果 | 证据 / 限制 |
| --- | --- | --- |
| Git 初始状态 | 干净 | main / origin/main，b71ee59，只有初始化文档 |
| core 固定来源 | 完成 | core 0.3.0，Git rev 5816bb5d18bc38bd26cca1ed6e7c5c093bfacd92，Cargo.lock 保持 Git 来源；27 个上游文件原样 vendor，含来源 / tree / SHA-256；无相邻路径依赖 |
| Linux Rust 检查 | 通过 | Rust/Cargo 1.99.1，cargo check、fmt、Clippy all-targets/all-features -D warnings、rustdoc |
| Rust mock 集成 | 10 项通过 | 串行执行线程、RCS1 持久化/恢复、敏感三策略、失败写入不 applied、存储失败终止、队列/关闭、generation 防过期写入、panic 停止、未标记六位文本正常接收；新增待处理事务 UUID 保留及测试构造函数目的地限制 |
| 测试反向控制 | 确认失败 | 临时反转过期写入断言，测试明确报告 0 != 1，然后恢复并全量通过 |
| 独立源码快照 | 通过 | 将纳入源码的文件复制到 /tmp 独立目录，cargo check --workspace --locked 成功，无相邻 repo |
| Kotlin / Swift 绑定生成 | 通过 | UniFFI 0.32.2 同一个 Linux cdylib 生成两个语言；修复 Kotlin close 名称冲突 |
| 实际 host FFI 调用 | 4 项通过 | 生成 Python 绑定加载 .so；跨线程回调、RCS1、关闭/释放、Storage 与 InvalidInput 类型映射及意外外部异常转换；无网络/真实剪贴板 |
| Android 原生 ABI | 通过 | GitLab pipeline #116 / job #268：arm64-v8a、x86_64 release；APK 内 Rust / JNA / AndroidX 共 6 个 so 的 ELF 16 KiB LOAD 对齐通过 |
| Android Kotlin/Compose / lint / APK | 通过 | pipeline #116 / job #268：应用和测试 APK、lint（0 错误，2 条依赖更新提示）、zipalign -P 16 通过 |
| Kotlin 模拟器绑定 / 适配器测试 | 6 项通过 | job #262 / core 0.3.0 完整 APK，全新 API 31 / x86_64 AVD；内存绑定、重复关闭、主队列执行/超时/撤销、隔离文件落盘/过大状态拒绝 |
| Android Compose 空界面启动 | 通过 | 同一 AVD，冷启动 Status: ok，未打开会话、未输入凭据 |
| iOS Swift / XCFramework | 未编译 | 无 macOS / Xcode；脚本在 Linux 明确退出，不宣称 iOS 通过 |
| iOS 移动端范围 | 配置检查通过 | project.yml 经 Ruby YAML 解析：只有 iOS 目标、iPhone / iPad 设备族；显式关闭 Mac Catalyst 和 Designed for iPhone / iPad 的 Mac 运行目的地；未运行 Xcode |
| Swift 模拟器绑定 smoke | 未运行 | 已编写 XCTest，无 Apple 运行环境 |
| Android 真实服务互操作 / 恢复 | 通过 | #116 / #268；固定服务端 2.0.0、真实 Android HTTPS、3 项业务方法、2 次强制终止准备阶段、设备及服务重启；剪贴板为 mock |
| 真机 / OS 平台适配 | 未验证 | Keystore/Keychain、真实 OS 剪贴板、已连接界面生命周期及真机仍待独立验证 |

## 清理

本次尝试在仓库 .tools 下载的 JDK、Gradle、Android command-line tools（约 1.1 GiB）、
本次新建的 ~/.gradle 缓存（约 177 MiB）、临时 wrapper 工程已删除；新增的两个 Android
Rust target 已卸载。本机 Android SDK / NDK 许可未接受、平台包未安装。
Gradle wrapper 已保存于源码，distribution SHA-256 与官方校验一致。
本次验证生成的 target/build 产物（约 2.3 GiB）也已删除；保留可重建源码及脚本。
现有 Rust 工具链与 Cargo 依赖缓存不作破坏性清空。
远端 API 31 验证容器、其中的系统镜像 / AVD / JDK / Gradle 缓存及导出 ZIP 已删除；
合成测试证据保存在 docs/evidence。Runner 使用的基础 SDK 镜像缓存和 GitLab 构建产物保留，
未清理其他项目、未修改全局 Runner / DNS / 代理配置。

## 远端开发构建

用户已确认私有镜像与现有 Runner。[开发镜像](https://gitlab.infinifar.top/Cassius/rustclipsync-mobile)
为 private，项目 ID 9。Git SSH 使用 gitlab-rocky:20820；同机管理 SSH 使用 turbo-rocky:22，
root 用户配合现有 turbo-rocky 密钥，不修改 SSH 配置。GitLab 状态/日志已改用 SSH 查询。

现有实例 Runner #3，标签 android，Docker executor。pipeline #99 在缺失的默认镜像
android-builder:api37 拉取失败，未执行项目代码。没有修改 Runner 全局配置；本项目显式指定
Cirrus Android SDK 镜像及固定 digest eba21147d4ca3860e020ef607e3072f188674227e3110eb1d5aeb659f9ce4140。
仅新镜像关闭 Auto DevOps，没有部署任务。

[pipeline #100](https://gitlab.infinifar.top/Cassius/rustclipsync-mobile/-/pipelines/100)，提交 8cefed9，
[job #236](https://gitlab.infinifar.top/Cassius/rustclipsync-mobile/-/jobs/236)：JDK 17.0.20.1、Rust 1.99.1、
SDK 36、build-tools 36.0.0、NDK 28.2.13676358；Rust 全检查、8 项测试、4 项 ABI smoke 和两个
Android 原生 ABI 通过，随后 Gradle 分发包下载超时。未把该轮记为 Android 应用编译通过。

CI 在临时容器安装工具，不向构建宿主安装 SDK；每轮清理 .ci-tools、target、Python 测试库和
APK 解包检查目录，测试报告及成功 APK 的 GitLab artifacts 配置 expire_in: 7 days。
实例“保留最新产物”策略可能延长保留期。

## 初始 Android 开发构建（core 0.2.0）

[pipeline #107](https://gitlab.infinifar.top/Cassius/rustclipsync-mobile/-/pipelines/107) /
[job #249 与产物](https://gitlab.infinifar.top/Cassius/rustclipsync-mobile/-/jobs/249)，
提交 `489d3e0f8486f1af1f69735310b8c7f986de99bf`。从干净 CI checkout 使用仓库 vendor，
Rust fmt、Clippy、8 项 mock、rustdoc、4 项 host ABI、绑定生成、两个 Android release ABI、
Kotlin / Compose、lint、debug APK / test APK 及 ZIP / ELF 16 KiB 对齐全部通过。

- JDK 17.0.20.1、Rust 1.99.1、SDK 36、build-tools 36.0.0、NDK 28.2.13676358。
- app-debug.apk：39,153,649 字节；SHA-256
  `96c8e3d9f741119c994cbee0b2b305b0abde15c53d564699fae75d50d215a4cc`。
- app-debug-androidTest.apk：824,629 字节；SHA-256
  `a80cb9e2775a0a24d1572b5452b81a3b2dbaf0d1814b1618d8478baaa2f45e22`。
- 下载后在隔离容器重新计算应用 SHA-256，与 CI 的 APK-SHA256SUMS 一致。
- lint 仅提示 coroutines 与 JNA 有更新版本；当前固定组合通过构建。云备份与设备迁移
  按 Android 12+ data-extraction-rules 明确排除所有应用数据，状态仍位于 noBackupFilesDir。

中间轮次暴露过默认 Runner 镜像缺失、Gradle / GitHub 网络超时、GitLab DNS 波动和
`OsConstants.O_DIRECTORY` 非公开 SDK 符号。项目固定镜像、校验 Gradle 镜像包、vendor core、
重试源 checkout，并改用公开 Os.open / fstat / S_ISDIR / fsync API 后完整通过。
这些失败轮次未被计为 Android 编译通过。

## Android 12 隔离模拟器

同一 job #249 的应用与测试 APK，经哈希复核后运行 scripts/test-android-emulator.sh。
Docker 容器仅授予 /dev/kvm，无特权模式、宿主目录挂载或对外端口。
AOSP API 31 default x86_64 revision 3，emulator 36.3.10.0，KVM，SwiftShader，
每轮创建新 AVD；6 项 instrumentation 测试全部通过（0.144 秒），空 Compose 配置页冷启动
Status: ok（TotalTime 999 ms）。这些时间仅是该次日志，不能据此宣称性能指标。

[原始合成测试、启动和 ELF 报告](evidence/android-api31-2026-11-01.txt) 记录 APK 与脚本哈希。
运行环境是 **4 KiB 页**；16 KiB 结果仅为 APK ZIP / ELF 静态检查，未在 16 KiB 设备执行。
没有访问系统剪贴板、真实 Keystore、服务端、账户或生产配对；未验证已连接会话的真实
前后台切换、Keystore / Keychain、粘贴权限、网络失败、重启恢复和桌面端互操作。


## 2026-11-01：core 0.3.0 与当前 `/api` 接口

将 core pin / Cargo.lock / Cargo source replacement 更新到已发布上游提交
`5816bb5d18bc38bd26cca1ed6e7c5c093bfacd92`，以维护脚本重新导出原样 27 文件快照，
Git tree 为 `9a83b788d0cff151e9045e694a98f324e39fcb47`。除 crate 版本与文档外，
上游代码仅调整 7 处请求路径；公开 Rust 类型、状态机及 RCS1 格式没有变化。
平台和 facade 继续调用 core，未新增路径拼接、版本选择或 `/v1` 回退。

- 旧依赖下运行新 HTTPS fixture，确认实际收到 `POST /v1/devices`，与预期 `/api/devices`
  不符并返回 404，测试失败；升级后，根地址与部署子路径的 14 次真实 HTTPS 请求全部通过。
- `scripts/check.sh` 通过：fmt、Clippy all-targets/all-features -D warnings、8 项 facade mock、
  14 次路由检查、rustdoc、Kotlin / Swift 绑定生成、4 项 host FFI smoke。
- 在独立临时目录对完整 vendor 快照运行 `cargo test --locked --offline --all-features`，
  core 自身 45 项测试通过，包含 `reads_state_written_by_unmodified_omaclipsync_020`。
- 空 Cargo Git 缓存下 `cargo metadata --locked --offline` 成功，解析到 0.3.0 的 vendor 路径。
- [合成 HTTPS 路由报告](evidence/core-api-2026-11-01.txt)；临时证书、随机 loopback 端口、
  固定合成响应，不读写真实账户或剪贴板。该检查不代表真实服务端互操作通过。

旧 pipeline #107 及其模拟器记录对应 core 0.2.0；新依赖的 Android 构建 / 运行验证另记如下。
iOS 仍未进行 Xcode 编译验证。


### core 0.3.0 的 Android 构建与模拟器

[pipeline #112](https://gitlab.infinifar.top/Cassius/rustclipsync-mobile/-/pipelines/112) /
[job #262 与最新 APK](https://gitlab.infinifar.top/Cassius/rustclipsync-mobile/-/jobs/262)，
实现提交 `d12e8806d363ff88420771ad54b5745ab0e12fae`：共享检查与 14 次 HTTPS 路由验证、
双 ABI、Kotlin / Compose、lint、应用与测试 APK、ZIP / 6 个 so 的 ELF 16 KiB 对齐全部通过。
lint 仍为 0 错误、2 条依赖更新提示。

- app-debug.apk：39,153,649 字节；SHA-256
  `648799e3c05a9e0aabd095940859133672bb53ecfa9ab4d870fcc2d934437dec`。
- app-debug-androidTest.apk：824,629 字节；SHA-256
  `ab872a4f69e50d646381a773cb188272b4e6e46340379a15c02d19656ffe09c8`。
- 下载后复核 SHA-256；CI 的 core-api-routes.txt 与本地路由证据逐字节一致。
- 同一完整 APK 在全新 API 31 x86_64 AVD 上运行，6 项合成 instrumentation 测试和空
  Compose 页面冷启动通过；[原始记录及哈希](evidence/android-api-migration-2026-11-01.txt)。
- Android 测试使用内存 Platform 和临时文件；没有执行真实 OS 剪贴板、Keystore 或实际
  网络服务互操作。iOS 未执行 Xcode 编译，16 KiB 仍仅为静态对齐检查。

本轮完成后删除验证容器（含 AVD / SDK 测试包）、导出 ZIP、本机 target / build 与临时日志；
保留仓库证据及 GitLab 构建产物，不清理共享镜像或现有 Cargo 缓存。


## 2026-11-01：Android 与真实隔离服务互操作

[pipeline #116](https://gitlab.infinifar.top/Cassius/rustclipsync-mobile/-/pipelines/116) /
[job #268 与普通 APK、测试包、服务端产物](https://gitlab.infinifar.top/Cassius/rustclipsync-mobile/-/jobs/268)，
实现提交 `b65e0adeba075b846523a103a39a60f5108d269a`。全部构建在远端完成：Rust 1.99.1
fmt / 严格 Clippy / 10 项测试 / rustdoc、14 次路由 smoke、4 项 host FFI、普通与独立测试
Android APK、两个 ABI、lint 与普通包 16 KiB 静态检查通过。

上游服务端固定到 2.0.0 / `c0e1774adef185ee7e2979cb6ad20c1c244acbed`，从带 SHA-256
验证的 GitHub 归档构建，不读取相邻 server 工作区或生产部署。运行位于一次性 Docker 容器，
仅传入 /dev/kvm，没有宿主数据挂载或公开端口；临时 SQLCipher 数据库、合成账户、临时
自签名 TLS 代理都在容器 loopback。仅测试包的受限入口支持回环证书；正式构造函数拒绝
该证书，普通包两个 Rust ABI 均确认没有测试构造函数符号。

| 实际运行场景 | 结果与证据 |
| --- | --- |
| 普通 APK / 绑定回归 | 6 项 instrumentation smoke 通过；Android CLI 启动空界面并取得 layout |
| 普通发送 / 接收 | Kotlin → Rust actor → core → HTTPS → 真实服务端，UTF-8 往返通过 |
| 敏感三种策略 | Disabled 不写入、不进收件箱并 skipped；EncryptedInbox 保存 RCS1、skipped、不自动写入；显式 AutoWrite 返回 applied |
| 收件箱与重启 | 设备重启后保留同一条收件箱内容及 cursor，恢复不重放；显式复制保留 sensitive，删除不回退游标 |
| 断网 / latest-only | 移除 adb reverse 后两次发送均报 Network，latest 持久化；强制停止活跃进程，Android reboot 后取消旧未决请求，只提交 latest，revision 从 3 变 4 |
| 本地敏感限制 | Disabled 下仍为 marked 的本地快照报告 Clipboard；模拟普通本地复制后，未标记六位文本正常接收 |
| 提交结果未知 | 真实服务端提交 revision 7 后，TLS 代理丢弃响应；客户端保存未决事务并被 force-stop |
| 进程 / 服务重启恢复 | 同一数据库重启实际服务端，epoch 不变；客户端用相同 UUID 重试，服务端返回 duplicate=true，revision 仍为 7 |
| 身份与游标 | 三个合成设备（含观察者）的 ID 集合保持不变；receiver 恢复并只应用最新项一次 |
| 负向控制 | 相同 Android 场景在注入注册 503 时明确失败，移除故障后通过 |

业务验证由 3 项完整 instrumentation 方法完成；两项崩溃准备方法在状态落盘后被故意
force-stop，instrumentation 中断不计为 JUnit 通过。源码恢复测试发现并明确保留了
Disabled 对本地 marked 快照的读取限制，测试更换为普通本地复制后继续；没有修改 core。
Android CLI 1.1 未发现临时 ANDROID_AVD_HOME 的设备，启动改用 SDK emulator；CLI 继续
用于应用启动和 UI layout。运行 API 31 / x86_64 / 4096 字节页，不代表 16 KiB 设备运行。

新增共享 `retryPending` 仅调用 core::Session::flush，不生成新复制事件或 UUID，Android
增加重试入口并在网络错误后刷新本地 pending 状态。新 Rust 测试在实现前因缺少该 API
编译失败，实现后通过。测试 constructor 的 feature 默认关闭，且仅接受明确的回环根地址。

原始证据：[执行来源与脚本哈希](evidence/android-service-2026-11-01/execution.json)、
[结构化结果及请求元数据](evidence/android-service-2026-11-01/report.json)、
[6 项 smoke 与 3 项业务报告](evidence/android-service-2026-11-01/instrumentation.txt)、
[负向控制](evidence/android-service-2026-11-01/negative-control.txt)、
[普通 / 测试包符号检查](evidence/android-service-2026-11-01/artifact-symbols.txt)。

没有调用 OS 剪贴板或生产 Keystore；状态使用 Android 独立目录与公开合成 key。
这不代表真实剪贴板权限、Keystore、已连接 UI 操作、前后台切换、真机或 iOS 已验证。
脚本已删除运行时 AVD、数据库、证书并停止其进程；外层验证容器、构建缓存和 ZIP 在
保存证据后清理，保留 GitLab 构建产物，不清理共享镜像或其他项目。

普通 APK SHA-256：`5b624d37d35444af1c85503c3dbc07f2258002a79cd035bcc896529c3b7da676`。

独立测试 APK SHA-256：`bc94dc26618b0d48de29dfb34a592578c420b2b96633fec9260e901f8e40b5a9`。

## 后台监听、写入与收件箱通知（通过，2026-11-01）

最终代码 `fb4bcaf8a5726047c7c74186f9e65163a7f3e106` 在
[私有 GitLab #125](https://gitlab.infinifar.top/Cassius/rustclipsync-mobile/-/pipelines/125) /
[job #284](https://gitlab.infinifar.top/Cassius/rustclipsync-mobile/-/jobs/284) 构建通过。
core 固定 `1a8ec6b97cc925fcbbbf51f9b8c8e31f97f1ea4e`，来自 core 仓库
`codex/mobile-background-inbox` 分支的两个本地提交，未推公共上游。完整校验 vendor
保证干净克隆可构建，不需要相邻仓库或取得该未公开 Git 对象；不直接修改 vendor。

| 验证层级 | 结果与范围 |
| --- | --- |
| 本地 Rust core | 49 项合成测试、fmt、Clippy all-targets / warnings denied 通过 |
| facade / host FFI（本地与 CI） | 12 项 Rust 测试；CI 另通过 4 项 host FFI smoke、14 个真实 HTTPS 路由请求、Kotlin / Swift 绑定生成、rustdoc、fmt / Clippy |
| Android 平台编译（远端） | 普通 / 独立测试 APK、arm64-v8a / x86_64、lint、16 KiB APK / ELF 静态对齐、普通包无测试构造符号检查通过 |
| Android 12 / API 31 模拟器 | 9 项 smoke、空 Compose 界面启动、4 项完整服务场景通过 |
| Android 15 / API 35 模拟器 | 9 项 smoke、空 Compose 界面启动、5 项完整服务场景通过；含系统前台服务超时 |
| 未验证 | 真机、真实 OS 剪贴板 / Keystore、完整表单与开关点击流程、iOS / Xcode 编译；iOS 推送后续配套 |

服务场景继续使用固定真实服务端、合成账户、内存剪贴板和独立 RCS1 文件，覆盖普通
收发、敏感跳过 / 收件箱 / 显式自动写入、断网 latest-only、进程 / 设备 / 服务端重启、
不确定提交同 UUID 去重。两个保存准备阶段故意 force-stop，不计作 JUnit 通过；相同
Android 场景在注入注册 503 时失败的负向控制也不计入通过数量。

新增后台场景实际启动前台服务，由 30 秒调度器完成首次接收，再验证默认 `**`、显示 /
隐藏、替换消息后旧按钮失效、熄屏隐藏、后台显式复制与敏感标记、后台自动写入开关、
收件箱策略优先于自动写入，以及停止后撤销会话与通知。模拟剪贴板在失去焦点时拒绝
读取，写入必须具备自动写入授权或当前消息的临时复制授权；计数证明后台没有读取。
通知操作通过真实 PendingIntent / NotificationManager，前台服务使用模拟器系统。
服务端 revision 8 / 9 / 11 只有 self + skipped 确认，显式复制没有变成 applied；普通
后台写入 revision 10 为 self + applied。

API 35 将系统 dataSync 时限临时改为 2,500 ms，验证超时后开关关闭、服务停止、会话
关闭、写入授权撤销，随后恢复设置。它是系统超时分支验证，不是连续运行六小时测试。
两套镜像实际页大小均为 4,096，不代表 16 KiB 设备运行验证。

早期 job #278 后台方法在 revision 9 后等待超时，后来将复制改为直接服务操作；job #282
在 API 35 发现两项通知测试误把异步 cancel 当成同步，已改为有上限的等待。失败探测与
只运行六项旧 smoke 的诊断结果单列，不计入最终通过数量。

最终证据：

- [执行信息与清理记录](evidence/android-background-2026-11-01/background-execution.json)、[CI 日志](evidence/android-background-2026-11-01/ci-284.txt)。
- [API 31 报告](evidence/android-background-2026-11-01/api31/report.json)、[后台场景](evidence/android-background-2026-11-01/api31/backgroundNotificationsAndOptionalWrites.txt)。
- [API 35 报告](evidence/android-background-2026-11-01/api35/report.json)、[系统超时](evidence/android-background-2026-11-01/api35/foregroundServiceTimeoutStopsAndRevokes.txt)。
- [失败探测说明](evidence/android-background-2026-11-01/probes.json)、[权限与 Intent 安全记录及 diff](evidence/android-background-2026-11-01/security-alignment.md)。

普通调试 APK SHA-256：`e5426f89e926b1f2a70658422d47e7d951b1234b36c2f49def49d54f8db2b6bd`。
运行脚本删除了 AVD、证书、数据库和相关进程；外层任务容器与所有 GitLab / 宿主 ZIP
导出文件已删除，本地 mobile target / 新增工具已清理。共享镜像、Runner、GitLab、
v2raya 保留，宿主与 Runner 配置未改。GitLab 构建产物按项目保留策略保留。

## 收件箱通知可见性与 GitHub 构建迁移（2026-11-01）

用户反馈只有后台监听通知。本轮确认旧实现进入前台时删除卡片，随后只在新 inbox ID
到达时发布；已有收件箱不会恢复。此外，系统把监听与收件箱自动合组，动作隐藏且更新后
组再次折叠。修复按持久化 inbox 元数据协调通知，回前台仅隐藏正文，使用独立 group key，
并提供状态说明、手动重显与版本 / 构建号。没有更改协议、core 或 RCS1。

实际运行的四个 APK 均来自提交 `71179403242589d44325e8093d1887dbcd7aa7a2` 的
[GitLab #131 / job #292](https://gitlab.infinifar.top/Cassius/rustclipsync-mobile/-/jobs/292)。
普通包为 **0.1.1 · 71179403**，SHA-256
`ebd546d29a504ba764a7a0ef97c573d6ae48d70c7e9eb4a92dd06a79d9cd1158`。
同一 job 通过 fmt、严格 Clippy、12 项 Rust 测试、rustdoc、14 次真实 HTTPS 路由请求、
4 项 host FFI smoke、绑定生成、两个 Android ABI、普通与隔离测试包编译、lint、16 KiB
APK / ELF 静态检查和测试构造函数隔离检查。iOS 仅生成绑定，未运行 Xcode。

| 当前产物的运行验证 | 结果 |
| --- | --- |
| API 31 / Android 12 | 10 项普通 smoke、空 Compose 界面、5 项完整隔离服务方法通过；真实 SystemUI 显示 / 隐藏 / 复制通过 |
| API 35 / Android 15 | 10 项普通 smoke、空 Compose 界面、6 项完整隔离服务方法通过 |
| 通知栏实际交互 | API 35 实际点击显示、隐藏、复制，截图确认独立卡片、默认 ** 与相邻文本按钮；复制由合成适配器确认全文和敏感标记，后台自动写入仍关闭 |
| 未验证 | 真机、真实 OS 剪贴板 / Keystore、完整连接表单操作、iOS / Xcode 和 16 KiB 设备运行 |

新增通知方法先保存合成敏感消息，关闭 native 会话，再重开相同 RCS1；不发送新消息也
必须恢复通知。服务回归继续覆盖敏感三策略、断网 latest-only、强制进程终止、Android
重启、真实服务端重启、丢响应后同 UUID / revision 去重、后台写入授权与系统超时。
通知方法不用 PendingIntent.send 代替点击 SystemUI；host 按实际层级或截图定位按钮。
服务只监听一次性容器 loopback，剪贴板 / 密钥为 mock，未访问生产服务或真实账户。

本轮早期测试工具探测未计入最终数量：API 31 UI 查询的并发 UiAutomation 冲突、两次
人工阶段超时、Android CLI 未取得通知窗口的结果均单列。定位发现通用“expand”匹配误选
`expandableNotificationRow`，会打开应用，已限定为准确的 `android:id/expand_button`。
GitLab #129 / #130 分别在隔离服务源站及 Maven DNS 解析失败，也不计作完整构建通过。
API 35 系统超时仍采用 2,500 ms 注入并还原；两种镜像均为 4,096 字节页。

用户随后要求改用 GitHub 托管构建，已推送 `f9428b17dad34381a2718fe899b2335c64390a31`：
[Android APK](https://github.com/Infinifar/rustclipsync-mobile/actions/runs/41334613641)、
[Rust facade](https://github.com/Infinifar/rustclipsync-mobile/actions/runs/41334613232)。
两个 GitHub job 均已成功，普通 APK / 测试 APK、lint、两个 ABI、静态对齐和 Rust 全检查通过；
[APK 与校验值下载](https://github.com/Infinifar/rustclipsync-mobile/actions/runs/41334613641/artifacts/13094973024)。
工作流另通过 Ruby YAML 解析与 actionlint 1.7.12；官方 actions 固定完整 SHA，依赖缓存、
同分支取消过时任务、7 天 artifacts、按需隔离 fixtures 已配置。GitLab 镜像改为 web / API
手动备用，避免双重自动构建。GitHub 构建结果单独记录，不能继承上述 APK 的运行通过结论。

证据：

- [执行来源、APK / 脚本哈希与清理](evidence/android-notification-ui-2026-11-01/execution.json)、[CI292 日志](evidence/android-notification-ui-2026-11-01/ci-292.txt)。
- [API 31 报告](evidence/android-notification-ui-2026-11-01/api31/report.json)、[API 35 报告](evidence/android-notification-ui-2026-11-01/api35/report.json)。
- [默认隐藏](evidence/android-notification-ui-2026-11-01/api35-hidden.png)、[显示正文与隐藏按钮](evidence/android-notification-ui-2026-11-01/api35-shown.png)、[再次隐藏](evidence/android-notification-ui-2026-11-01/api35-hidden-again.png)。
- [探测范围与失败说明](evidence/android-notification-ui-2026-11-01/probes.json)、[通知权限与 Intent 安全记录及 diff](evidence/android-notification-ui-2026-11-01/security-alignment.md)。

## Android 0.1.2 / MyUI 反馈修复

本轮仅修改 Android：通知 toggle 不再请求系统认证以保持展开，复制保留认证路径；
专用透明单色小图标；同步/收件箱/设置三页、可持久化的三态主题选择、动态明暗配色、
安全区域和键盘滚动适配，收件箱删除增加确认。协议、Rust、vendor 不变。
NotificationSafetyTest 增加 toggle 与 copy 认证标志断言，本轮只编译该测试，不宣称已运行。
源码 XML 解析、diff 检查完成后统一触发 GitHub APK / lint 和 Rust CI。
用户要求耗时视觉测试交其操作，故没有启动模拟器或截图，不宣称 MyUI 真机行为已通过。

本轮提交：`df37e1ca1bdaf454b059fd581f9f4f6685a80af9`（0.1.2）。
[Rust CI](https://github.com/Infinifar/rustclipsync-mobile/actions/runs/41337695078) 已通过。
[Android APK 构建](https://github.com/Infinifar/rustclipsync-mobile/actions/runs/41337695083)
已统一触发；本次记录时正在编译 / lint，完成后在该页面下载产物。
不启动持续监控，不把构建中记为平台验证通过。用户视觉验收清单见 ANDROID-REVIEW.md。

## Android 0.1.3：方向、过滤、设备 UUID 与本地化

本轮补齐 Android 双向/仅发送/仅接收、core 正则过滤编辑与样例测试、设备 UUID、检查更新
和项目入口、中英文与跟随系统语言；原生通知移除副标题，独立于正文长度的星号占位按宽度
和字体重算。界面按“同步/收件箱/设置”，设置再分“同步/过滤/外观/关于”。

共享 facade 增加 API，UniFFI 生成文件需重新生成；没有修改 core、vendor、协议或 RCS1。
方向在 Rust 串行执行器上拦截；只接收下重试只取消旧发送候选/完成确认，不发送旧上传。
过滤复用 core::Filter，保留敏感策略切换时的规则，无效规则不覆盖现有配置。

验证：

- 本地 Rust fmt、Clippy all-targets/all-features -D warnings、15 项全特性测试、rustdoc 通过。
- 新过滤/UUID 测试先因 API 缺失失败，实现后通过；方向负向控制断言反转，明确报告 Paused != Downloaded，恢复后通过。
- 167 个英/中文资源键与格式占位符、XML、diff、Python 语法检查通过。
- Android 增加纯 JVM 单元测试（版本比较、方向）；instrumentation 断言适配动态星号与本地化，但本轮不运行设备测试。
- 检查更新不使用任何同步凭据；私有/未发布与已是最新版分开报告。测试不访问实际网络、剪贴板或用户账户。
- 前一轮 0.1.2 的 Rust / Android GitHub 两项构建已通过，本轮新 Android / FFI 编译结果另行记录，不能复用旧结果。
- 用户明确耗时视觉测试交其执行，本轮不启动模拟器或截图，不宣称 MyUI/Android 16 真机、本轮 iOS 编译通过。

Rust 原始记录见 [本轮证据目录](evidence/android-client-settings-2026-11-01/)。

最终实现 `40dabeaedefd755ffc3e7952ec57709392080d43`（0.1.3）：
[Rust CI 已通过](https://github.com/Infinifar/rustclipsync-mobile/actions/runs/41341560187)，
[Android APK / JVM tests / lint 构建](https://github.com/Infinifar/rustclipsync-mobile/actions/runs/41341560080)
现已确认成功，APK 可在同页面下载。该结果仅适用于 0.1.3。
本地临时 Rust target 与日志已清理，原始验证记录保留在证据目录；未安装本地 SDK，
未启动模拟器、真实剪贴板或生产服务。支持 RTL 布局方向，但尚未提供其他语言翻译，
也未做相应视觉验收。现有常驻通知会随返回前台刷新语言，服务配置变化会重算隐藏占位。


## Android 0.1.4：统一后台运行与 Shizuku 来源

本轮将“后台监听+后台写入”收敛成一个默认关闭的“后台运行”，方向控制全部收发。
旧设置不静默升级授权。敏感三策略互斥且持久保存，UI 不再保留可能过时的策略副本；
收件箱页显示缺失条件、实际策略、最近结果及接收过滤，不再暗示所有开关都需开启。

用户选择 Shizuku 来源。固定 API/provider 13.1.5，显式授权、Binder 断开/撤销处理、
非 daemon UserService、调用 UID 校验、私有 AIDL、受平台权限保护的 Provider、
无正文变化回调、锁屏/熄屏保护、256 KiB RPC 上限和 1.5 秒工作线程等待。
当前支持无线调试/ADB shell 模式，root 模式明确提示改用 ADB，避免 UID/包所有权错误
被误认为空剪贴板。未使用任意命令执行，不改用户系统配置。

共享 sync_background 串行组合 core 既有操作，核心 27 个 vendor 文件校验未变。
会话内观察记录防止轮询重复创建复制事件，并记录成功 OS 写入防止监听器迟到回传；
网络 UUID、重试、取消、ack、敏感与过滤逻辑仍由 core 负责，协议及 RCS1 未变。
Android 对本地复制使用系统时间戳观察标记，同样文本的真正再次复制仍可推送；成功 OS
接收保留 core origin，因此不因晚到监听器或轮询反复回传。解绑后显示已授权待启用状态。

已完成的本地检查：

- fmt、Clippy --workspace --all-targets --all-features -D warnings、rustdoc。
- 本轮另运行固定 core 的 49 项测试，全部通过。
- Rust 全特性 18 项通过（默认 17 项）；覆盖三方向、已持有事务断网续传、特权读取失败
  不清除待发项、继续敏感三策略接收、仅接收不读，以及重复检查不重复上传/替换原 UUID。
- 负向证据：新 API 缺失先编译失败；去重未实现时合成同一文本重复上传 3 次，断言失败。
  修复后通过。所有正文、账号、key 为合成数据，未访问真实系统剪贴板。
- Kotlin/Swift/Python UniFFI 重新生成，4 项真实 host ABI smoke 通过；不是平台运行验证。
- 隔离回环 TLS 路由 smoke 14 个 /api 请求通过；不是生产服务或完整服务端行为验证。
- 179 个中英文资源键/占位符、XML 和 diff 静态校验通过。

证据见 [本轮原始记录](evidence/android-background-model-2026-11-01/)。Android 的应用、测试 APK、
JVM 测试与 lint 统一提交 GitHub 构建，最终链接/状态随后记录；不复用旧包代表本轮成功。
没有本地 SDK 安装、模拟器、截图、真实剪贴板、Shizuku 设备运行或生产账户测试。
按用户要求，可视化和 moto razr 60 u / Android 16 / MyUI 验收交用户，清单见 ANDROID-REVIEW.md。
iOS 没有 macOS/Xcode 条件，未声称平台编译或真机通过。


最终代码 `7b9b460670f58fa246f23dc9348be2f62a4bdff0`（Android 0.1.4 / versionCode 5）：
[Rust CI 41374038027 已通过](https://github.com/Infinifar/rustclipsync-mobile/actions/runs/41374038027)；
[Android APK / JVM / lint / 隔离测试源码编译 41374038090](https://github.com/Infinifar/rustclipsync-mobile/actions/runs/41374038090)
后续确认在 PrivilegedClipboardService.kt:47 因混合类型反射参数的 reified 类型推断而编译失败，没有生成 APK。修复与协议 3 升级的后续结果见下文。
没有开启 watch 或持续轮询；前一个 93bb23a 的构建被最终代码替代，不能作为本轮产物。
临时 Rust target、host 绑定和参考源码已清理，路径清单见本轮 evidence/cleanup.json。
GitHub/GitLab 代码镜像均已推送；不修改远端共享 Runner、缓存或代理。


## 2026-11-03：协议 3、构建修复与安卓外观

- 固定共享 core 0.4.0 正式提交 `6a1ccd37b8ca3e515610d48bfa47f20b7c9b0ca4`，通过维护脚本重新导出完整、未修改的 31 个文件，校验 Git 来源与逐文件 SHA-256；Cargo.lock 固定新的依赖图。没有手改 vendor。
- facade 在数据访问前通过 core 设置方向并等待确认；显式方向修改复用 core 的控制协议。元数据 status 不激活方向；后台首次使用仍须完成前台 check。拒绝不兼容的服务端，并通过新的 FFI 错误和中英文资源显示协议版本提示。
- 全特性 facade 23 项 Rust 测试通过，fmt、Clippy workspace/all-targets/all-features `-D warnings`、rustdoc 通过。5 项新增协议测试分别验证方向上报、ACK 失败时不读取／上传剪贴板、较小的授权投递版本、错误分类及关闭敏感同步后的普通接收；对 5 项实际断言分别反转后均实测失败，恢复后全套通过。
- Kotlin/Swift 绑定重新生成，4 项 Python → Rust → Python host UniFFI 测试通过。它们不证明 iOS 平台编译或 Android 系统剪贴板正确性。
- 回环真实 HTTPS 路由探针共 22 个请求通过，覆盖根路径／子路径、协议头、方向 ACK、WSS 拒绝后的 HTTP 降级、长轮询前租约刷新、数据会话头和撤销。
- 将隔离服务端源码固定为正式 3.0.0／`9ed68997476575fd042f3ed6807cfec4931ebbea`，更新来源归档 SHA-256。Python 观察者复用同一协议门禁；用本机独立临时数据库实际验证注册、方向确认、撤销后重新连接及服务端重启。没有启动模拟器。
- 修复 `PrivilegedClipboardService.kt:47`：两种反射签名的数组均声明 `arrayOf<Any?>`，保留原 Binder 调用者 UID、锁屏和 ADB Shizuku 模式检查。
- 启动图标改为来自 Omaclipsync 的内置自适应矢量及单色层，浅色／深色背景分别白色／黑色；应用主背景同样使用纯白／纯黑，保留动态强调色。180 个中英文资源键／占位符和 XML 校验通过，并使用已有缓存 AAPT2／API 36 实际编译、链接全部资源及清单。
- 没有重新安装 SDK、Gradle 或改变主机配置；完整 Android 双 ABI、Kotlin、lint 和 APK 对齐检查交 GitHub Actions。没有进行本轮模拟器、真机、Shizuku 或截图验收；iOS 无 Xcode 编译结果。

## 2026-11-03：Android 保存连接与 Shizuku 授权显示

- 确认旧 UI 仅以 Compose 内存保存账号/密码，点击连接还会清空密码，未实现持久化。
  新版在成功连接后使用独立 Keystore AES-256-GCM key 加密保存服务器/账号/密码，
  AtomicFile 原子替换；应用级 controller 异步恢复，读取失败不抹除或覆盖，显式删除与
  core 配对和收件箱隔离。不自动连接，不保存到 SavedState Bundle 或普通偏好设置。
- Shizuku 的授权状态与来源状态分离，匹配请求码、显式拒绝覆盖 SDK 旧授权缓存，
  Binder 死亡撤销状态；新授权仍须通过服务身份/版本/权限探测。
- 10 项本地 JVM 测试通过：加密往返、随机 IV、防篡改/截断/错误密钥/版本/大小检查，
  授权成功显示、等待/拒绝/重授权/撤销/死亡/异常/不支持模式。
  在临时源码中分别移除密码恢复和忽略授权回调，回归测试按预期失败；原源码不改动。
- SecureConnectionStore 已使用本机现有 Android 36 public API jar 编译通过；这不是
  Gradle APK 构建，也不代表 Android Keystore 实机成功。191 项中英文键及占位符检查通过。
- 新增 4 项 Android instrumentation 测试覆盖新 store 重开与删除隔离、文件损坏及
  密钥丢失不重置、controller 恢复与显式删除、读取失败保护；本轮仅交 Actions 编译，
  不启动模拟器、不读取真实凭据/剪贴板、不操作手机。既有隔离服务测试注入内存凭据存储。
- Rust/core/vendor/FFI、iOS 和敏感消息策略未修改；不以此前 facade 测试替代本轮平台验证。
  完整 Android APK、lint 和测试编译由推送后的 Actions 运行，不持续监控。

## 2026-11-03：任务移除、前台接收与待处理事务

- 用户确认 6e456dbe 已连接、游标 990、有待处理事务；从最近任务划掉后后台设置关闭。
  确认 onTaskRemoved 调用 configureBackgroundRunning(false) 会持久化关闭；改为保留
  设置及有效服务，超时只结束当前会话。进程被杀/强行停止/重启仍需前台重新连接。
- 确认原 pollBackground 无条件跳过前台。启用并连接的会话现会在有焦点时处理待办并
  自动接收，使用普通前台适配器；不因此启用 Shizuku 前台读取或自动前台发送。
  tick 完成后等 3 秒，连接完成和焦点恢复时唤醒，不承诺固定网络延迟。
- 新 Rust 回归测试在修复前实际返回 Conflict 失败；修复后覆盖冲突、过期重试、
  保留原剪贴板、恢复后不重发旧文本，以及后续敏感消息仅入收件箱且零系统写入。
  调用 core.recover_upload_conflict，不改 vendor、协议、状态格式或 FFI 公共 API。
- 13 项本地 JVM 测试通过，含前台接收/未连接/失焦/仅发送/忙碌的调度准入，以及
  移除任务的服务保留；临时恢复前台跳过和任务移除停止，测试按预期失败。
- instrumentation 新增设置跨 controller、close、timeout 保留及显式关闭验证，
  隔离服务用例增加自动前台普通/敏感接收并更新超时预期；本轮只交 Actions 编译，
  不启动模拟器或操作用户真实剪贴板、账户和手机。

本轮最终检查：24 项 Rust 全特性测试通过、fmt、workspace Clippy
all-targets/all-features -D warnings 和 rustdoc 通过；191 个翻译键检查通过。
13 项 JVM 测试使用现有缓存工具执行，不安装 SDK/Gradle。Android APK、lint 和
instrumentation 编译由新 Actions 验证；尚未在 moto/MyUI 实机验证。

## 2026-11-03：独立 App 暂停及 0.1.5 正式发布

用户确认 fe6c031 已实机验证，授权独立 App 增加暂停、合并 main 并发布；小企鹅
合并 master、沿用原签名发布 0.1.5。本轮新增暂停/恢复 UI，偏好持久化，方向与暂停
通过现有 core 0.4.0 协议上报；原方向 API/DTO 不变，新增 UniFFI set_paused 方法。
平台额外撤销读/自动写权限，暂停上报失败也保持本地阻断，恢复确认后才放行。
既有收件箱可按当前 ID/epoch/focus 显式复制；暂停不自动清空收件箱或设备身份。

27 项 Rust 测试通过，包含各收发入口、后台捕获、方向切换保持暂停、失败上报不放行、
在途接收失效、保留身份/收件箱及确认后恢复；移除 pause admission 的负向控制按预期失败。
完整 check.sh 通过：fmt、workspace Clippy all-targets/all-features -D warnings、
31 个 vendor 来源校验、196 个翻译键、27 Rust、22 HTTPS fixture、rustdoc、
Kotlin/Swift 绑定生成与 5 项 host FFI（含暂停不触网/不碰剪贴板）。
Android 新增暂停重建持久化 instrumentation，只交 CI 编译；本轮未启动模拟器或
读取真实剪贴板/账户，不以 host 测试宣称手机暂停按钮已通过实机验收。

独立 Android 正式密钥用现有 JDK keytool 生成 RSA-3072 PKCS12，四项 Actions
Secrets 已设置。私钥/密码只在仓库外私有目录和 Secrets，版本库只提交公钥证书/摘要。
新签名工作流仅允许 main，完整检查后构建非调试 release、核对证书/包名/版本及两 ABI
16 KiB 静态对齐；先建草稿并回下载校验附件，再正式发布。此前 debug 签名不能直接
覆盖，新正式签名将长期复用；不自动卸载设备或删除数据。实际发布状态以 Actions 为准。

## 2026-11-03：0.1.5 Android JVM 编译冲突修复

正式发布 run 41577743431 的 Rust/FFI 检查通过，但 compileDebugKotlin 失败：
SessionController.paused 的属性 setter 与新增 setPaused(Boolean) 方法同为 JVM
setPaused(Z)V。修正 Android 控制入口为 configurePause，更新 UI 和 instrumentation
调用；Rust/UniFFI 的 MobileSession.setPaused 保持不变，暂停语义不变。
使用系统 JRE 与既有缓存 Kotlin compiler 复现旧名冲突，并验证改名后的最小委托属性
用例可编译；未重新安装 JDK，不把该验证当成完整 Android APK 编译或设备测试。
完整 APK 与正式签名发布重新从修复后的 main 触发，成功后仍发布 0.1.5。

## 2026-11-03：仓库公开及 1.0.0

用户要求公开 Infinifar/rustclipsync-mobile，删除该仓库失败构建记录并发布独立 Android
1.0.0。发布前扫描 633 个历史 blob，未检出私钥块、常见 GitHub token、已生成的
当前签名密码/密钥库 Base64，未发现被跟踪的 .env 或 p12/pfx/jks/keystore 文件。
该检查不是对所有类型凭据的完整安全审计；签名私钥仍只在私有备份和 Actions Secrets。
成功的 0.1.5 Release/构建保留，失败记录按用户要求删除，旧失败 run 链接可能失效。
1.0.0 仅更新版本与发布说明，versionCode 6 → 7，沿用同一证书；发布工作流仍会对照
上一正式版证书及内部版本递增，完整检查和回下载校验后自动发布。本轮本地运行 shell
语法、翻译与版本一致性检查，未为版本号变化重复运行未改动的 Rust 测试或安装本机 JDK。
