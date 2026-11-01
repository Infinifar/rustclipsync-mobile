# 开发与构建

仓库包含共享 facade、Kotlin / Compose Android 工程和 Swift / SwiftUI iOS 工程。
平台源码已接入绑定和适配器，但平台编译状态以 [验证记录](VALIDATION.md) 为准。
所有 smoke 测试使用 synthetic.invalid、合成密钥、内存剪贴板，不需要真实账户。

## Linux / macOS 共享层

需要 Git、C 编译器、Rustup、Python 3。rust-toolchain.toml 固定 Rust 1.99.1；
Cargo.lock 固定依赖，包括远端 core 的完整提交。首次构建需要网络读取 GitHub 和 crates.io。

```sh
cargo test --workspace --locked
scripts/check.sh
```

check.sh 依次执行 fmt、Clippy、Rust 测试、rustdoc、双端绑定生成和 host UniFFI ABI smoke。
生成文件在忽略的 build/bindings；不要手改生成代码。host smoke 额外生成 Python 绑定，
验证跨 FFI 回调/错误/资源释放，不是 Android 或 iOS 平台运行验证。

## Android 开发构建

产品基线：Android **12 / API 31** 起，applicationId `io.github.infinifar.rustclipsync`。
构建组合固定为 JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin / Compose compiler 2.3.10、
compile/target SDK 36、build-tools 36.0.0、NDK 28.2.13676358，arm64-v8a 与 x86_64。
Compose BOM 2026.02.01，Activity 1.12.4，Lifecycle 2.10.0；这些是本次选定的构建组合，
不表示最新版本。平台编译前仍需验证该组合，见验证记录。

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
在 16 KiB 真机工作。正式发布签名、商店流程和后台常驻功能不在首版内。

有隔离模拟器时运行：

```sh
cd android
./gradlew connectedDebugAndroidTest
```

BindingSmokeTest 只用内存 Platform，通过 Kotlin → Rust → Kotlin 验证创建、RCS1 状态和
关闭；不访问模拟器真实剪贴板、Keystore 或生产服务。平台适配器的 Keystore、实际剪贴板、
断网/重启和权限行为需要后续独立测试设备验证，不能用这项 smoke 替代。

使用行为：输入 HTTPS 地址与账户，点击连接（首次注册以当前服务端版本为基线），然后
显式发送/接收。敏感默认跳过；加密收件箱与自动写入分开选择，自动写入有确认。
离开前台立即请求关闭，不在后台监听剪贴板。回到前台重新连接；首版不保存账户密码。
更换服务器不会静默清空旧身份，现有状态与服务器不匹配会报错；多账户/显式重新配对 UI 待做。

## GitLab 开发构建

按用户要求，以 `gitlab-rocky` 的 GitLab CI 做 Android 开发构建，不在本机安装 Android SDK。
该 SSH alias 是 GitLab Git 服务，不能作为普通 shell / rsync 构建主机。
当前仓库 origin 仍是 GitHub；开发镜像路径和 Runner 配置须确认后接入。

.gitlab-ci.yml 已提供 Rust 检查和 Android 开发 APK 任务；Android 任务选择 **android** 标签，
要求专用开发 Runner 预装以上 SDK / NDK / JDK、Rustup，且已接受对应工具许可。
此标签是待配置契约，不代表已发现存在的 Runner；不要给生产部署 Runner 扩权或改配置。
Rust Docker job 使用 rust:1.99.1-bookworm，需要 Python 3。构建产物保留 7 天，无部署 job，
不读取生产变量、发布密钥或生产账户。接入前确认 Runner executor 是否支持 image / tags。

## iOS（需要 macOS）

工作基线：iOS **18** 起，bundle ID `io.github.infinifar.rustclipsync`。
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
- [Xcode 支持表](https://developer.apple.com/support/xcode/)
- [UIPasteboard](https://developer.apple.com/documentation/uikit/uipasteboard)
- [Keychain 前台可访问性](https://developer.apple.com/documentation/security/ksecattraccessiblewhenunlockedthisdeviceonly)
- [SwiftUI scene background](https://developer.apple.com/documentation/swiftui/scenephase/background)
- [GitLab CI 语法](https://docs.gitlab.com/ci/yaml/)
