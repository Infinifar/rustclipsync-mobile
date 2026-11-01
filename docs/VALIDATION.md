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
| Android 原生 ABI | 未编译 | 本地工具安装已撤销，按用户要求改 GitLab 开发构建；远端项目/Runner 待确认 |
| Android Kotlin/Compose / lint / APK | 未编译 | 已建工程与构建检查脚本，不能宣称 APK 可运行 |
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

## 待完成的远端验证

`ssh -T gitlab-rocky` 返回 GitLab @Cassius；该端点拒绝普通 shell 命令。
`Cassius/rustclipsync-mobile` 查询未找到/无访问权，网页需要登录。
待用户确认开发镜像或已有项目、Runner 标签与工具链后触发 CI，并把 pipeline/job 链接、
提交哈希、APK 哈希及各检查结果补入本记录。准备了 CI 文件不表示已运行 CI。
