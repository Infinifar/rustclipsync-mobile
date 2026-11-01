# 验证记录

日期：2026-11-01。所有测试使用合成数据；没有读取真实剪贴板、账户、配对状态或密钥。

| 层次 | 结果 | 证据 / 限制 |
| --- | --- | --- |
| Git 初始状态 | 干净 | main / origin/main，b71ee59，只有初始化文档 |
| core 固定来源 | 完成 | Git rev a13298c550fa4ebb287e55fe7a229486a36f22f5，Cargo.lock 保持 Git 来源；27 个上游文件原样 vendor，含来源 / tree / SHA-256；无相邻路径依赖 |
| Linux Rust 检查 | 通过 | Rust/Cargo 1.99.1，cargo check、fmt、Clippy all-targets/all-features -D warnings、rustdoc |
| Rust mock 集成 | 8 项通过 | 串行执行线程、RCS1 持久化/恢复、敏感三策略、失败写入不 applied、存储失败终止、队列/关闭、generation 防过期写入、panic 停止、未标记六位文本正常接收 |
| 测试反向控制 | 确认失败 | 临时反转过期写入断言，测试明确报告 0 != 1，然后恢复并全量通过 |
| 独立源码快照 | 通过 | 将纳入源码的文件复制到 /tmp 独立目录，cargo check --workspace --locked 成功，无相邻 repo |
| Kotlin / Swift 绑定生成 | 通过 | UniFFI 0.32.2 同一个 Linux cdylib 生成两个语言；修复 Kotlin close 名称冲突 |
| 实际 host FFI 调用 | 4 项通过 | 生成 Python 绑定加载 .so；跨线程回调、RCS1、关闭/释放、Storage 与 InvalidInput 类型映射及意外外部异常转换；无网络/真实剪贴板 |
| Android 原生 ABI | 通过 | GitLab pipeline #107 / job #249：arm64-v8a、x86_64 release；APK 内 Rust / JNA / AndroidX 共 6 个 so 的 ELF 16 KiB LOAD 对齐通过 |
| Android Kotlin/Compose / lint / APK | 通过 | pipeline #107 / job #249：应用和测试 APK、lint（0 错误，2 条依赖更新提示）、zipalign -P 16 通过 |
| Kotlin 模拟器绑定 / 适配器测试 | 6 项通过 | job #249 完整 APK，全新 API 31 / x86_64 AVD；内存绑定、重复关闭、主队列执行/超时/撤销、隔离文件落盘/过大状态拒绝 |
| Android Compose 空界面启动 | 通过 | 同一 AVD，冷启动 Status: ok，未打开会话、未输入凭据 |
| iOS Swift / XCFramework | 未编译 | 无 macOS / Xcode；脚本在 Linux 明确退出，不宣称 iOS 通过 |
| Swift 模拟器绑定 smoke | 未运行 | 已编写 XCTest，无 Apple 运行环境 |
| 真机 / OS 平台适配 / 服务互操作 | 未验证 | 尚需隔离服务、测试设备，验证 Keystore/Keychain、OS 剪贴板、生命周期、断网与重启 |

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

## 已通过的完整 Android 开发构建

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
