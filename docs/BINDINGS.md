# 共享绑定与会话契约

实现：UniFFI **0.32.2** proc macros，同一个 `rustclipsync-mobile-ffi` crate 生成 Kotlin / Swift。
core 固定到 `5816bb5d18bc38bd26cca1ed6e7c5c093bfacd92`，版本 0.3.0；来源写在 workspace
Cargo.toml 和 Cargo.lock。为避免 CI 到 GitHub 的 Git 连接反复超时，.cargo/config.toml 将该
来源替换为同提交的完整 vendor 快照，保留许可证、Git tree 与逐文件 SHA-256 清单。
快照按固定 Git 对象导出，不读取相邻 checkout 作为构建依赖，不修改 core。
core 0.3.0 使用 `/api/...`，无 `/v1` 回退；移动 facade 的公开 API 与 RCS1 持久化格式保持不变。

## 评估结论

UniFFI 可以表达对象、record、枚举、Result、异步方法和可由两端实现的 foreign trait。
已从 Linux cdylib 实际生成 Kotlin、Swift；host Python 绑定用于跨 FFI 回调运行测试，
不进入产品架构。当前没有采用手写 JNI / C ABI 的必要限制。

发现的限制与处理：

- Kotlin 生成类已有 AutoCloseable.close；导出同名 Rust close 会生成冲突方法。
  使用 **requestClose()** 请求关闭，**shutdown()** 异步等待清理；destroy 只回收绑定引用。
- UniFFI async 不自动把阻塞工作移到后台。所有 core 调用在 facade 的独立执行线程上，
  exported future 只投递命令并等待 oneshot，不执行阻塞网络或磁盘调用。
- 0.32 的全局 `--config` 不接受旧式平面配置。生成器从 crate 的 uniffi.toml 读取配置。
- Swift 使用 Swift 5 language mode，供现代 Xcode 编译。UniFFI 的 Swift 6 严格并发支持
  仍有边界；本仓库尚未进行 Swift 编译，不能据生成成功宣称 Swift 6 验证完成。

依据：[UniFFI async](https://mozilla.github.io/uniffi-rs/latest/internals/async-overview.html)、
[foreign traits](https://mozilla.github.io/uniffi-rs/latest/foreign_traits.html)、
[Kotlin 生命周期](https://mozilla.github.io/uniffi-rs/latest/kotlin/lifetimes.html)、
[Swift 支持](https://mozilla.github.io/uniffi-rs/latest/swift/overview.html)。

## 线程、回调与取消

一个 MobileSession 只有一个 core Session 所有者。16 个命令的有界队列使用 try_send；
满时返回 Busy，不堵塞调用线程。构造只校验 key 长度并创建线程；初始化、Client 构造和
StateStore.load/save 也在执行线程。status() 等待初始化完成；构造/状态查询没有网络调用。
check、sendClipboard、retryPending、receive 才执行 core 的网络方法。

Platform 是 UniFFI foreign trait：clipboardSnapshot、clipboardWrite、loadState、saveState
都返回带固定安全文案的 AdapterError；UnexpectedUniFFICallbackError 转成 Platform 错误。
不将平台异常的正文带到 Rust 或 UI。Rust 保留最近的 core SyncError 原因链直到会话退出，
不向 UI 返回底层 URL、凭据或剪贴板正文。状态仅含 paired、cursor、hasPending、inbox 元数据。
readInbox 单独读取正文；首版 UI 默认不显示正文，只提供显式复制和删除。

剪贴板适配器在 Rust 执行线程接到请求，向 UI 主线程投递短操作，最多等待两秒。
超时任务必须撤销，尚未开始的任务不得稍后写入。已经进入 OS 调用的超时属于可能已写入，
core 的 applying journal 按歧义写入恢复，不能当作成功确认。平台离开前台时先 revoke
适配器，再 requestClose，避免排队回调触碰失效 UI。适配器只保留应用上下文或弱会话引用，
不保留 Activity / View；监听器只递增 clipboardChanged 计数，不读正文。

core 在网络前后的快照检查和 generation 检查保持原样，facade 不缓存剪贴板快照。
发送只 record_local + flush，冲突不被自动重置或覆盖。retryPending 只委托 core::Session::flush，
不生成新复制事件或 UUID；取消未决旧请求后，core 可能仍有待发最新项，需要再次显式重试。
Android 在网络错误后刷新本地状态，显示待处理事务与重试入口。没有重新实现协议、重试、确认、
游标、过滤或冲突解决逻辑；也没有给首版增加长轮询或自己的自动重试循环。

取消一个等待中的 Kotlin coroutine / Swift Task / foreign future **不保证撤销已入队命令**，
因为它可能已进入 core 事务。生命周期必须明确调用 requestClose；UI 用 epoch 检查丢弃
过期结果。平台包装类串行等待旧 shutdown 完成后才可打开同一状态存储，避免两个 Session
同时写同一文件。不同包装类不得共享同一 store；首版单窗口界面只创建一个 owner。

## 关闭和 panic

requestClose 是幂等的非阻塞请求：拒绝新命令、递增 generation、唤醒执行线程。
在途 core 操作可能完成事务落盘，排队命令与在途迟到结果返回 Closed；不再启动剪贴板调用。
shutdown 是可重复、可并发 await 的共享完成 future，在线程释放 core 与平台适配器后完成；
不在调用线程执行 Thread.join / Worker.join。Drop 只作为补充发出关闭请求。

每次 HTTP 请求由 core 设有限超时（普通请求 10 秒；changes 长轮询 30 秒，首版不使用）。
一个命令可能包含多个请求，不能把单请求超时描述成整个 shutdown 的固定时限。
平台 SDK 卡住或进程被系统终止不属于 Rust 能保证正常退出的情形。

release 与 dev 使用 unwind；执行线程最外层 catch_unwind 在 panic 后结束整个会话，
shutdown 返回 Internal，不继续使用可能损坏的 core Session。UniFFI 自身也有 ABI panic
转换，但非 throwing 方法遇到内部 panic 仍可能导致 Swift fatal error。abort、OOM 和
进程终止无法被 catch_unwind 捕获。错误、状态和普通日志不携带正文；禁止打印生成的
MobileConfig / ClipboardText（部分语言的生成 record 自带包含字段的字符串表示）。

## 存储和敏感策略

平台仅存放 core::state::seal 返回的原始 RCS1 字节，core::state::open 负责读取。
64 MiB 上限沿用 core；只有 ENOENT / 对应的明确不存在错误返回 None。
Android 在 noBackupFilesDir 用 Keystore AES-GCM 包装随机 32 字节 state key；iOS 用
WhenUnlockedThisDeviceOnly、不可同步 Keychain 条目。明文账户密码只留在会话内存，
首版尚不提供记住密码。平台临时文件写入、同步、原子 rename 和目录同步后才报告成功。
密钥丢失、解密失败、权限错误或损坏不重置身份；Storage 错误终止本会话，必须从磁盘重开。

Disabled → core 跳过标记内容；EncryptedInbox → core 加密收件箱、skipped ack、无 OS 写入；
AutoWrite → 显式启用，OS 实际接受写入后才 applied ack。手动复制收件箱保留敏感标记，
不改变已消费游标，不伪装成自动写入，也不自动删除条目。切换策略不重放已消费消息。

Android 使用官方 IS_SENSITIVE extras。iOS 只读取本应用的显式自定义标记，没有通用的
密码检测接口；写入使用 localOnly，敏感写入设置 60 秒过期。平台提示/过期不构成阻止其他
应用读取的安全边界。未标记文本按普通文本处理；不推断密码、TOTP 或特定复制来源。


## 独立的隔离服务测试入口

Cargo feature `disposable-tests` 默认关闭。仅开启时导出 `forDisposableTest`，并且只接受
`https://127.0.0.1:<非零端口>/`；它调用同一 core Client 的自签名证书测试选项。
普通构造函数继续严格验证 TLS。独立测试 APK 的 applicationId 以 `.disposable` 结尾，
CI 检查普通包两个 Rust ABI 都没有该测试构造符号，测试包两个 ABI 都包含它。
正式打包不要启用该 feature；测试包及其绑定不用于发布。

Android 服务测试通过真实 Kotlin → Rust → core HTTPS 调用，剪贴板是内存 Platform，
存储使用独立 noBackup 子目录内的原始 RCS1 与公开的合成 key，不调用生产 Keystore。
在 Disabled 策略下，本地快照仍有敏感标记时，core 会报告 Clipboard 错误；测试先验证
拒绝行为，再模拟普通本地复制，确认未标记六位文本按普通内容接收。
