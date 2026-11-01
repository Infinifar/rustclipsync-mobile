# 下一会话开发说明

开始先依次阅读 AGENTS.md、README-ZH.md、ARCHITECTURE.md、本文件，再看 Git 状态。
不要覆盖尚未提交的实现；本仓库已经超出初始化阶段，但不能宣称平台应用已验证。

## 已完成

- Cargo workspace；core 0.2.0 固定 Git rev a13298c550fa4ebb287e55fe7a229486a36f22f5，锁文件。
- UniFFI 0.32.2，同一 facade 生成 Kotlin / Swift；额外 Python 绑定仅用于 host ABI smoke。
- 单执行线程 / 16 命令有界队列，非阻塞 requestClose 与重复 await shutdown，存储失败停止。
- core Session 的 check / record_local / flush / pull / 敏感收件箱复用，无协议重写。
- 8 项 Rust synthetic mock 测试、4 项实际 host FFI smoke 通过；fmt / Clippy / rustdoc 通过。
- Android Compose 工程、Keystore 包装 key、RCS1 原子落盘、剪贴板标记、UI 回调撤销。
- SwiftUI 源码、Keychain、原子落盘、剪贴板与 scene 生命周期；XcodeGen / XCFramework 脚本。
- 双端内存适配器绑定 smoke 测试源码，待平台环境执行。
- GitHub Rust CI 与 GitLab Rust / Android 开发构建配置，尚未代表远端已运行。

## 用户基线与远端构建

标识均为 io.github.infinifar.rustclipsync。用户确认最低 Android **12 / API 31**，不承担旧 Android
兼容包袱；iOS 依同样意图采用 **iOS 18** 工作基线。首批前台手动发送/接收、状态、加密
敏感收件箱，先 Android 后 iOS，离开前台关闭。未标记文本不可承诺识别敏感。

用户要求使用 **gitlab-rocky 做开发构建，并清理本地新增工具**。
它是 GitLab SSH endpoint，认证为 @Cassius，不是普通 shell 主机。
本次尝试安装的本地 JDK / SDK tools / Gradle 和缓存已清理，SDK 许可未接受。
不能重新在本机安装来绕过这项偏好。

用户已确认创建 **Cassius/rustclipsync-mobile 私有开发镜像** 并使用现有 Runner。
镜像已创建，项目 ID 9，GitHub origin 保持不变；gitlab remote 指向 gitlab-rocky。
网页确认现有实例 Runner #3（Android API 37 Docker Runner），标签 android，在线。
新镜像已关闭 Auto DevOps，仅运行仓库声明的开发检查与调试 APK，不修改 Runner 配置。
正在启动 CI 读取实际镜像/工具链并验证；结果见 VALIDATION.md。

## 紧接着做

1. 落实远端项目、Runner/executor、JDK / SDK / NDK，并实际跑 Android ABI、Kotlin、lint、APK
   和 16 KiB 检查。先看 BUILD.md 的固定组合，遇兼容问题按官方文档调整后更新验证记录。
2. 在隔离模拟器执行 Kotlin BindingSmokeTest；增加平台适配器的超时撤销与存储故障测试。
3. 在 Mac 选择实际 Xcode，执行 build-ios-native.sh、XcodeGen、xcodebuild 和 XCTest。
   Swift 源码未编译，不要把生成 Swift 绑定与 iOS 编译混淆。
4. 用隔离服务器和合成账户完成与桌面端的协议互操作、断网/重启、冲突、真实 OS 权限验证。
   暂不接生产、真实剪贴板或现有配对配置。
5. 后续产品补齐显式重配对/多账户与冲突决策 UI，不能默默删除状态或自动覆盖远端。

修改共享代码后运行 scripts/check.sh。平台生成文件不用手改；保持原协议与 RCS1。
详见 BINDINGS.md 的关闭、回调、取消和存储契约。所有验证证据写入 VALIDATION.md，
分别标注 mock、真实 ABI 调用、平台编译、模拟器和真机。
