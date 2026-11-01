# 验证记录

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

## 后台监听、写入与收件箱通知（实施中，2026-11-01）

core 固定到 `1a8ec6b97cc925fcbbbf51f9b8c8e31f97f1ea4e`，从上游源码仓库的
`codex/mobile-background-inbox` 分支导出完整校验快照；core 公共远端尚未发布该提交。
本地 core 49 项合成测试、mobile facade 12 项测试及 fmt / Clippy 已通过。
Android 新增实现与 instrumentation 场景已编写，平台构建和模拟器结果等待远端验证；
不得将之前的 6 项手动同步 smoke 或 3 项服务互操作结果算作此功能验证。
iOS 后台推送后续实施，未做本功能的 iOS 平台编译或真机验证。
