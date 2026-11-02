# 开发与构建

仓库包含共享 facade、Kotlin / Compose Android 工程和 Swift / SwiftUI iOS 工程。
平台源码已接入绑定和适配器，但平台编译状态以 [验证记录](VALIDATION.md) 为准。
所有 smoke 测试使用 synthetic.invalid、合成密钥、内存剪贴板，不需要真实账户。

## Linux / macOS 共享层

需要 Git、C 编译器、Rustup、Python 3 与 OpenSSL CLI（隔离 HTTPS 测试）。rust-toolchain.toml 固定 Rust 1.99.1；
Cargo.lock 固定依赖，包括 core 的完整提交。core 使用同一提交的完整 vendor 快照，
来源、Git tree 和 SHA-256 见 vendor/core-provenance.json 与逐文件清单；常规构建不再拉取
GitHub core 仓库，其余依赖仍通过 crates.io。不得直接改 vendor，更新方法见 vendor/README.md。

```sh
cargo test --workspace --locked
scripts/check.sh
```

check.sh 先验证 vendor 未漂移，再依次执行 fmt、Clippy、Rust 测试、隔离 HTTPS 路由检查、
rustdoc、双端绑定生成和 host UniFFI ABI smoke。
生成文件在忽略的 build/bindings；不要手改生成代码。host smoke 额外生成 Python 绑定，
验证跨 FFI 回调/错误/资源释放，不是 Android 或 iOS 平台运行验证。

## Android 开发构建

产品基线：Android **12 / API 31** 起，applicationId `io.github.infinifar.rustclipsync`。
构建组合固定为 JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin / Compose compiler 2.3.10、
compile/target SDK 36、build-tools 36.0.0、NDK 28.2.13676358，arm64-v8a 与 x86_64。
Compose BOM 2026.02.01，Activity 1.12.4，Lifecycle 2.10.0；这些是本次选定的构建组合，
不表示最新版本。实际编译和运行证据见验证记录。

在开发 Runner 安装工具并由操作者接受 Android SDK 许可，然后：

```sh
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-17
export PATH="$JAVA_HOME/bin:$HOME/.cargo/bin:$PATH"
rustup target add aarch64-linux-android x86_64-linux-android
scripts/check-android.sh
```

该命令生成绑定、编译两个 Rust ABI、检查 ELF LOAD alignment、执行 Android lint / 应用与 instrumentation 测试 APK 编译，
并用 zipalign 检查 APK 16 KiB 对齐。APK 位于
`android/app/build/outputs/apk/debug/app-debug.apk`，使用开发调试签名，不用于发布。
脚本同时检查 JNA 和 Rust 所有打包 so 的 ELF 对齐；这些静态检查仍不足以证明整个 APK
在 16 KiB 真机工作。正式发布签名与商店流程待完成；可选后台运行受系统时限约束。

有隔离模拟器时运行：

```sh
cd android
./gradlew connectedDebugAndroidTest
```

BindingSmokeTest 只用内存 Platform，通过 Kotlin → Rust → Kotlin 验证创建、RCS1 状态和
关闭；另外 3 项 MainThreadGateTest 检查主队列执行、超时与撤销，2 项 SecureStateTest 在
临时目录检验合成密文的原子替换与过大状态拒绝。全部测试不访问 OS 剪贴板、Keystore
或生产服务。平台适配器的 Keystore、实际剪贴板、
断网/重启和权限行为需要后续独立测试设备验证，不能用这项 smoke 替代。

当前 core 0.4.0 使用 `/api/...`，要求协议 3／服务端 3.0.0；不回退到 `/v1`。
服务器配置输入部署根地址（例如 `https://example.test/` 或 `https://example.test/sync/`），
不附加 `/api` 或 `/v1`；端点拼接由 core 完成。

使用行为：输入 HTTPS 地址与账户，点击连接（首次注册以当前服务端版本为基线），然后
显式发送/接收。敏感默认跳过；加密收件箱与自动写入分开选择，自动写入有确认。
后台运行默认关闭，此时离开前台立即请求关闭，回到前台需重新连接。
开启后按同步方向继续收发，普通接收写入剪贴板；后台捕获新复制需要显式 Shizuku 授权。
SDK API/provider 固定 13.1.5，AIDL 通过 Gradle 生成。授权、故障降级和时限见 BACKGROUND.md。
首版不保存账户密码。
更换服务器不会静默清空旧身份，现有状态与服务器不匹配会报错；多账户/显式重新配对 UI 待做。

## GitHub Actions 开发构建

用户已将默认构建主机改为 GitHub 托管 Runner，不再依赖 Rocky 的网络环境。
开发分支为 `codex/mobile-native-foundation`，入口为
[Actions](https://github.com/Infinifar/rustclipsync-mobile/actions)。推送代码或发起 PR 自动执行：

- `Rust facade`：vendor 校验、fmt、Clippy、22 项 synthetic 测试、rustdoc、路由与 host FFI smoke、双端绑定。
- `Android APK`：Ubuntu 24.04、JDK 17、Rust 1.99.1、SDK 36 / NDK 28.2，两个 ABI、应用与测试 APK、lint、APK / ELF 静态对齐。

Actions 固定官方 action 提交，复用 Cargo / Gradle 缓存；同分支的新提交取消过时任务。
文档修改不重复构建。APK、SHA-256、源码 / run 信息与报告作为 artifact 保留 7 天；
每次运行的 Summary 提供下载链接，无需持续轮询。此处的构建不代表已运行模拟器或真机。
SDK 安装只使用托管镜像原有许可，脚本不自动接受新增条款。

隔离服务端与测试 APK 通过 `workflow_dispatch` 的 `fixtures` 选项按需生成；工作流文件
进入默认分支后可从 Actions 手动选择开发分支。普通自动构建不重复编译隔离服务端。
已完成的 Rocky 运行证据继续保留，不能把它当成 GitHub 产物的实机测试。

依据：[GitHub workflow 语法](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)、
[Ubuntu 24.04 镜像清单](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md)、
[setup-java 缓存](https://github.com/actions/setup-java)、[artifact 保留](https://github.com/actions/upload-artifact)。

## GitLab 手动备用构建

私有开发镜像：[Cassius/rustclipsync-mobile](https://gitlab.infinifar.top/Cassius/rustclipsync-mobile)，
代码位于 codex/mobile-native-foundation 分支。origin 为默认 GitHub 构建来源；gitlab remote 使用
`git@gitlab-rocky:Cassius/rustclipsync-mobile.git`（SSH 20820）。

通过同机 `ssh -l root turbo-rocky`（22）管理与读取本项目的 GitLab CI 状态/日志，
使用已有密钥，不需要浏览器或新建 API 凭据。现有实例 Runner #3，标签 **android**、Docker
executor；没有修改 Runner 全局配置。项目 Auto DevOps 已关闭，CI workflow 仅接受 web / API
显式启动，普通镜像推送不再触发构建。

.gitlab-ci.yml 使用固定 digest 的 Cirrus Android SDK 36-ndk 镜像，临时容器中安装 JDK 17、
Rust 1.99.1、NDK 28.2。先验证 Gradle 配置，再跑 scripts/check.sh 与 scripts/check-android.sh。
core-api-routes.txt 保留当前锁定 core 对根路径及部署子路径的 14 次真实 HTTPS 请求报告。
镜像原有 SDK 许可用于所需包；脚本不会自动接受新增许可。CI 使用可达的 Gradle 分发镜像，
wrapper 仍强制校验已从官方核对的 SHA-256；开发者本地的默认下载地址保持官方地址。

成功的 app-debug.apk、测试 APK、lint / 对齐报告和 SHA-256 的 artifacts 配置 7 天过期；实例保留最新产物策略可能延长该期限。
每轮删除 .ci-tools、target、Python 测试库和 APK 解包目录；不清空其他项目的缓存或镜像。
不读取生产变量、发布密钥或生产账户，不自动部署或推送 GitHub main。

独立测试主机可用 scripts/test-android-emulator.sh 启动全新的 Android 12 / API 31 AVD。
需要 /dev/kvm、SDK emulator 和 command-line tools；建议在一次性容器中运行，仅传入测试
APK 和脚本。它运行上述 6 项合成测试，打开空配置页检查启动，然后回到桌面并停止应用；
不连接服务器、不输入凭据、不读写 OS 剪贴板或密钥。
固定 SDK 镜像中的 emulator 36.3.10 使用该版本支持的 SwiftShader 模式；升级模拟器后按
[官方加速文档](https://developer.android.com/studio/run/emulator-acceleration) 核对参数。

## iOS 移动端（构建宿主为 macOS）

工作基线：iOS **18** 起，bundle ID `io.github.infinifar.rustclipsync`。
应用只面向 iPhone / iPad，工程关闭 Mac Catalyst 与 Designed for iPhone / iPad 的 Mac 运行目标。
macOS 客户端之后在桌面端仓库考虑；此处 macOS / Xcode 仅为 iOS 的构建工具要求。
Xcode 26.1 或更高、对应 iOS SDK、XcodeGen 2.x；Swift 5 language mode。
这不是已通过验证的 Xcode 版本声明。选择实际 Mac 工具链后记录 xcodebuild -version。

```sh
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
scripts/build-ios-native.sh
cd ios
xcodegen generate
xcodebuild -project RustClipSync.xcodeproj -scheme RustClipSync \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
# 在独立模拟器上运行 Tests/BindingSmokeTests.swift，再记录实际目的设备名称/ID。
```

构建脚本创建通用 simulator static library 与设备 library 的 XCFramework；生成源码和工程
均可重建，不提交 Generated、xcodeproj、开发者签名或个人 scheme 数据。
Linux 上脚本明确拒绝构建。Linux 生成 Swift 文件不代表 iOS 编译或真机通过。
iOS 会请求系统粘贴许可，不能承诺后台任意读取；离开 active scene 关闭会话。

## 官方依据（实施时查询：2026-11-01）

- [AGP 8.13.2 / Gradle / JDK 兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Compose compiler plugin](https://developer.android.com/develop/ui/compose/compiler)
- [Compose BOM 映射](https://developer.android.com/develop/ui/compose/bom/bom-mapping)
- [NDK 下载与版本](https://developer.android.com/ndk/downloads)
- [16 KiB 页大小](https://developer.android.com/guide/practices/page-sizes)
- [Android 安全剪贴板](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android 12+ 备份与设备迁移规则](https://developer.android.com/identity/data/autobackup#xml-syntax-android-12)
- [Xcode 支持表](https://developer.apple.com/support/xcode/)
- [Xcode 构建设置参考](https://developer.apple.com/documentation/xcode/build-settings-reference)
- [UIPasteboard](https://developer.apple.com/documentation/uikit/uipasteboard)
- [Keychain 前台可访问性](https://developer.apple.com/documentation/security/ksecattraccessiblewhenunlockedthisdeviceonly)
- [SwiftUI scene background](https://developer.apple.com/documentation/swiftui/scenephase/background)
- [GitLab CI 语法](https://docs.gitlab.com/ci/yaml/)
- [ADB 与 reverse](https://developer.android.com/tools/adb)
- [命令行 instrumentation 测试](https://developer.android.com/studio/test/command-line)
- [命令行模拟器](https://developer.android.com/studio/run/emulator-commandline)


## 隔离 HTTP 路由检查

`python3 scripts/test-core-api.py` 编译 workspace 的 core_api_smoke 示例，使用同一锁定 core
依次执行注册、状态、长轮询、正文、发送、取消和确认。测试夹具在 127.0.0.1 随机端口提供
一次性 TLS 证书与固定响应，分别检查根地址和无尾斜杠的部署子路径，共 14 个请求。
只核对真实 HTTP 方法、路径、查询与响应解析，不实现服务端状态机；它不是服务互操作测试。
仅该测试允许 loopback 自签名证书；产品的 core Client 继续校验证书。无真实账户或系统剪贴板。


## Android 与真实隔离服务互操作（远端 Linux）

隔离验证使用已成功的 GitLab 产物或 GitHub 手动 fixtures 产物。备用 GitLab 任务执行普通 Rust / Android 检查，
再用 scripts/build-disposable-server.py 从固定的上游 GitHub 源码归档构建服务端，
版本、提交和 SHA-256 在 tests/disposable-server-source.json。该脚本在临时目录构建，
不依赖相邻仓库或运行中的服务；产物为 build/disposable-server。

scripts/check-disposable-android.sh 保存普通 APK 和绑定后，临时启用 `disposable-tests`
以及 Gradle 的 `-PdisposableIntegration=true`，生成 build/disposable 内的独立测试 APK。
最终恢复普通 APK / 绑定的默认产物路径。CI 同时校验两种包的原生符号，确保普通包没有
回环证书测试入口。build/disposable 仅供测试，不能作为正式客户端分发。

在全新、无宿主数据挂载或公开端口的一次性测试容器中提供 /dev/kvm，安装 Android CLI，
使用 SDK 镜像中已有许可安装 AOSP API 31 default x86_64 系统镜像，然后放入同一 CI job 的
四个 APK、服务端二进制与来源记录、测试脚本，运行：

```sh
python3 scripts/test-android-service.py --android-cli /path/to/android
```

脚本拒绝已连接设备，自建临时 AVD。Android CLI 用于文档、应用启动及 UI layout；
CLI 1.1 未发现 ANDROID_AVD_HOME 下的临时设备，AVD 启动使用 SDK emulator 并由脚本持有
进程句柄。所有网络端点只监听容器 loopback，通过 adb reverse 接入；没有远程生产账号。
脚本使用真实的固定服务端、临时 SQLCipher 数据库与自签名 TLS 代理，覆盖：

- 正式构造函数拒绝自签名证书；注入注册 503 时，相同 Android 场景必须明确失败。
- 普通 UTF-8 发送/接收、敏感跳过、加密收件箱、显式复制与自动写入策略。
- 移除 adb reverse 产生连接失败，保存 first / latest，强制终止活跃进程并重启 Android。
- 恢复同一设备身份、游标、收件箱及离线 latest-only 事务，确认不自动重放。
- 真正提交 PUT 后丢弃响应，再强制终止进程、重启服务端，恢复时核对重复 UUID 仍对应
  同一 revision，服务端不增加第二条提交。

完整脚本包含 API 31 五项、API 35 六项 instrumentation 方法；另两项准备阶段在保存标记后被故意 force-stop，
其 instrumentation 中断是故障注入，不计为通过的 JUnit 测试。普通包十项 smoke 另行执行。
结果、请求元数据、APK / server 哈希保存在 build/service-e2e-apiN；不会记录 Authorization 或正文。
结束时停止 emulator / adb / TLS 代理 / 服务端，删除临时 AVD、证书与数据库。
外层操作者仍需删除一次性容器及导出的 ZIP；保留证据和 GitLab 构建产物。

这些是 Android 模拟器绑定、HTTPS 与隔离文件测试；不代替真实 OS 剪贴板、Keystore、
连接界面交互、真机、iOS 或 16 KiB 设备运行验证。


后台通知测试在普通测试包中新增 NotificationSafetyTest；完整远端脚本现支持
`--api 31`（默认）、`--api 35` 或 `--api 36`，输出分别为 build/service-e2e-apiN。
API 33+ 在一次性模拟器上为测试包授予通知权限；真实用户授权仍由应用 UI 申请，
通知频道与永久拒绝可从“通知权限与频道设置”恢复。新场景实际启动前台服务，
检查默认隐藏、显示 / 隐藏、旧消息票据、熄屏隐藏、显式复制、后台运行、敏感策略与停止撤销。
剪贴板和安全存储继续使用合成适配器与独立文件，不接触真实凭据或宿主剪贴板。

`restoredInboxActionsInNotificationShade` 先把合成敏感消息保存为 RCS1，关闭并重开会话，
不再发送新消息；服务启动后必须恢复已有收件箱卡片。宿主随后定位真正的 SystemUI
“显示 / 隐藏”和“复制”控件并点击，等待应用确认正文切换与模拟剪贴板写入。
这项检查补充了旧的 Notification 对象和 PendingIntent 测试，覆盖用户实际可操作的通知栏。

默认脚本自动完成点击。需要人工检查屏幕时加 `--manual-shade`，出现 `SHADE READY`
后使用 `scripts/notification_shade.py --android-cli ... --adb ... --output ...` 展开卡片，
再分别加 `--click 显示`、`--click 隐藏`、`--click 复制`。每一步需在 120 秒内完成。
`--shade-only` 只运行通知专项与普通 smoke，报告标记为 `notification-shade-only`，
不能代替发送、断网和重启的完整结果。Android CLI 1.1 返回底层应用窗口时，脚本回退到
SDK UiAutomator 当前 SystemUI XML，并保存层级以复查实际按钮位置。
部分 API 31 完整运行中，SDK 回退遇到 `UiAutomationService already registered`，外部查询
无法与现有测试连接并存；此时使用 `--manual-shade`，先检查真实截图，再按其坐标输入。
不要把 UI 查询失败计为通知操作通过，也不要把 host 清理时中断的 instrumentation 算作应用崩溃。

开发 APK 使用临时调试签名。本轮比较 job #286 / #292 的证书指纹，确认它们不同，
不能互相覆盖安装；换构建服务也不会自动沿用旧签名。尚未配置稳定的开发签名或发布签名，
不要为解决安装冲突自动卸载或删除用户应用数据。

Rocky 部分任务容器继承的 Tailscale DNS 不可达时，GitLab pre_get_sources_script
仅在该临时 Docker 作业内回退到 223.5.5.5 / 1.1.1.1，解决源码拉取前的解析失败；
不改 Runner 配置、宿主 DNS 或 v2raya。依据为 GitLab 官方 hooks:pre_get_sources_script。
