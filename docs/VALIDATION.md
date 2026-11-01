# 验证记录

日期：2026-11-01。所有测试使用合成数据；没有读取真实剪贴板、账户、配对状态或密钥。

| 层次 | 结果 | 证据 / 限制 |
| --- | --- | --- |
| Git 初始状态 | 干净 | main / origin/main，b71ee59，只有初始化文档 |
| core 固定来源 | 完成 | Git rev a13298c550fa4ebb287e55fe7a229486a36f22f5，Cargo.lock 远程来源；无相邻路径依赖 |
| Linux Rust 检查 | 通过 | Rust/Cargo 1.99.1，cargo check、fmt、Clippy all-targets/all-features -D warnings、rustdoc |
| Rust mock 集成 | 8 项通过 | 串行执行线程、RCS1 持久化/恢复、敏感三策略、失败写入不 applied、存储失败终止、队列/关闭、generation 防过期写入、panic 停止、未标记六位文本正常接收 |
| 测试反向控制 | 确认失败 | 临时反转过期写入断言，测试明确报告 0 != 1，然后恢复并全量通过 |
| 独立源码快照 | 通过 | 将纳入源码的文件复制到 /tmp 独立目录，cargo check --workspace --locked 成功，无相邻 repo |
| Kotlin / Swift 绑定生成 | 通过 | UniFFI 0.32.2 同一个 Linux cdylib 生成两个语言；修复 Kotlin close 名称冲突 |
| 实际 host FFI 调用 | 4 项通过 | 生成 Python 绑定加载 .so；跨线程回调、RCS1、关闭/释放、Storage 与 InvalidInput 类型映射及意外外部异常转换；无网络/真实剪贴板 |
| Android 原生 ABI | 通过 | GitLab pipeline #100 / job #236：arm64-v8a、x86_64 release 编译与 ELF 16 KiB LOAD 对齐通过 |
| Android Kotlin/Compose / lint / APK | 验证中 | pipeline #100 在 Gradle 分发包下载超时；调整超时并前置 Gradle 配置检查后继续 |
| Kotlin 模拟器绑定 smoke | 未运行 | 已编写内存适配器 instrumentation test，等待平台构建与隔离模拟器 |
| iOS Swift / XCFramework | 未编译 | 无 macOS / Xcode；脚本在 Linux 明确退出，不宣称 iOS 通过 |
| Swift 模拟器绑定 smoke | 未运行 | 已编写 XCTest，无 Apple 运行环境 |
| 真机 / OS 平台适配 / 服务互操作 | 未验证 | 尚需隔离服务、测试设备，验证 Keystore/Keychain、OS 剪贴板、生命周期、断网与重启 |

## 清理

本次尝试在仓库 .tools 下载的 JDK、Gradle、Android command-line tools（约 1.1 GiB）、
本次新建的 ~/.gradle 缓存（约 177 MiB）、临时 wrapper 工程已删除；新增的两个 Android
Rust target 已卸载。Android SDK / NDK 许可未接受、平台包未安装。
Gradle wrapper 已保存于源码，distribution SHA-256 与官方校验一致。
本次验证生成的 target/build 产物（约 2.3 GiB）也已删除；保留可重建源码及脚本。
现有 Rust 工具链与 Cargo 依赖缓存不作破坏性清空。

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
APK 解包检查目录，测试报告及成功 APK 使用 7 天过期的 GitLab artifacts。
