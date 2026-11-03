# RustClipSync Mobile

[English](README.md) | 简体中文

独立安卓剪贴板同步 App，采用 Kotlin / Jetpack Compose，并通过 Rust/UniFFI 复用
[rustclipsync-core](https://github.com/Infinifar/rustclipsync-core)。
Swift/SwiftUI iPhone/iPad 客户端仍在开发，本次只发布 Android APK。

[下载 Android 0.2.0](https://github.com/Infinifar/rustclipsync-mobile/releases/tag/v0.2.0)
· [发布说明](releases/0.2.0.md)
· [服务端](https://github.com/Infinifar/rustclipsync-server)
· [桌面客户端](https://github.com/Infinifar/rustclipsync-desktop)
· [Omaclipsync 插件](https://github.com/Infinifar/omaclipsync)

## 功能

- 双向、仅发送、仅接收，访问数据前向服务端上报方向并等待确认。
- **暂停／恢复同步**：停止两个方向、使在途剪贴板操作失效，保留方向、配对及加密收件箱。重开仍暂停；暂停上报失败也会阻断本地同步，恢复等待服务端确认。
- 连接成功后以独立 Android Keystore 密钥加密保存地址、账号和密码，重开恢复填写。
- 可选后台运行，启用后 App 前台也自动接收。后台读取其他 App 的新复制需要 **Shizuku 13+（无线调试／ADB shell 模式）**；接收无需 Shizuku。
- 已标记敏感文本三选一：跳过、加密收件箱、显式自动写入。收件箱通知默认隐藏正文，解锁后可显示／隐藏和复制。
- core 正则过滤、旧上传安全恢复、中英切换及系统／浅色／深色主题。使用 Omaclipsync S 图标和白／黑背景。

## 连接与使用

支持 **Android 12+、ARM64 与 x86_64**。需 **服务端 3.0.0／协议 3**，固定 core **0.4.0**。
服务器填写 HTTPS 部署根地址，不追加 `/api` 或 `/v1`，点击“保存并连接”。首次配对不重放旧消息。

在设置中选择方向及敏感策略。开启后台运行后自动接收：普通消息写入系统剪贴板，
敏感消息遵守所选策略；前台发送仍使用发送按钮。暂停会阻断自动及手动收发，仍可明确复制
已有的敏感收件项。暂停通过 core 清理未发送候选，已经提交的请求无法撤回。

划掉最近任务保留后台设置，系统允许时继续当前服务；Android 和厂商后台限制仍适用。
进程被杀、强行停止或重启后需前台重新连接。保存了凭据或授予 Shizuku 权限并不等于已连接。

同一手机只启用一个剪贴板同步客户端。使用独立 App 时，请关闭小企鹅输入法中单独的
RustClipSync 模块，避免两个客户端互相写入。

## 隐私与签名

不能可靠识别未标记的敏感信息。核对的 [Bitwarden 浏览器复制实现](https://github.com/bitwarden/clients/blob/main/apps/browser/src/platform/services/browser-clipboard.service.ts)使用普通文本接口，
未附加系统原生敏感标记；不使用密码格式猜测替代来源元数据。系统预览隐藏也不等于进入加密收件箱。

连接凭据与 core RCS1 状态分别加密存入不参与备份的私有文件。HTTPS 保护传输，
当前不提供端到端加密；不要公开凭据、设备令牌、状态密钥或剪贴板正文。

**升级**：0.2.0 与 0.1.5 沿用同一正式签名，可直接覆盖升级。此前同包名临时调试 APK 的签名不同，不能直接覆盖。请先阅读
[迁移说明](releases/0.2.0.md)，自行保留所需内容；本工具不会自动卸载或删除数据。
后续正式版沿用同一证书覆盖升级。[签名及公钥证书](SIGNING.md)。

## 开发与验证

[构建](docs/BUILD.md) · [架构](docs/ARCHITECTURE.md) · [绑定](docs/BINDINGS.md)
· [后台行为](docs/BACKGROUND.md) · [验证记录](docs/VALIDATION.md) · [实机验收](docs/ANDROID-REVIEW.md)

GitHub Actions 检查 Rust／FFI、协议 fixture、Android 编译、lint 及正式签名产物。
用户已实测现有后台模型；新增暂停功能由合成回归测试覆盖，实机行为仍需单独验收。
iOS 尚未通过 Xcode 编译或设备验证。

许可证：[MIT](LICENSE)。
