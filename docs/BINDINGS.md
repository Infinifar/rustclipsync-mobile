# 共享绑定与会话契约

实现：UniFFI **0.32.2** proc macros，同一个 `rustclipsync-mobile-ffi` crate 生成 Kotlin / Swift。
core 固定到正式提交 `6a1ccd37b8ca3e515610d48bfa47f20b7c9b0ca4`，版本 0.4.0；来源写在 workspace
Cargo.toml 和 Cargo.lock。为避免 CI 到 GitHub 的 Git 连接反复超时，.cargo/config.toml 将该
来源替换为同提交的完整 vendor 快照，保留许可证、Git tree 与逐文件 SHA-256 清单。
快照按固定 Git 对象导出，不读取相邻 checkout 作为构建依赖，不直接修改 vendor。
后台接收、方向控制与恢复能力均来自已发布的 core，不在 facade 或平台层重写。
core 0.4.0 使用 `/api/...` 并要求协议 3／服务端 3.0.0，无 `/v1` 或旧协议回退。
本次 Rust API 与 wire 协议有不兼容变化，设备身份及 RCS1 加密状态继续保留。
facade 在数据操作前设置方向并等待 core 的 ACK；`updateDirection` 同时修改服务端方向与本地限制。
`ProtocolUnsupported` 通过 UniFFI 与平台安全文案明确报告版本不兼容；会话失效与网络错误分别映射。

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
check、sendClipboard、retryPending、receive、receiveInbox、receiveBackground、syncBackground 执行 core 的网络方法。

Platform 是 UniFFI foreign trait：clipboardSnapshot、clipboardWrite、loadState、saveState
都返回带固定安全文案的 AdapterError；UnexpectedUniFFICallbackError 转成 Platform 错误。
不将平台异常的正文带到 Rust 或 UI。Rust 保留最近的 core SyncError 原因链直到会话退出，
不向 UI 返回底层 URL、凭据或剪贴板正文。状态仅含 paired、cursor、hasPending、inbox 元数据。
readInbox 单独读取正文；UI 默认不显示正文，收件箱通知仅在用户显式点击“显示”后读取当前正文。

剪贴板适配器在 Rust 执行线程接到请求，向 UI 主线程投递短操作，最多等待两秒。
超时任务必须撤销，尚未开始的任务不得稍后写入。已经进入 OS 调用的超时属于可能已写入，
core 的 applying journal 按歧义写入恢复，不能当作成功确认。未启用后台运行时，离开前台先
revoke 适配器，再 requestClose。启用后按统一方向收发；普通接收可写入，敏感内容三选一。
通知复制的单次授权绑定当前消息与预览版本，实际写入前重检解锁、票据、授权和生命周期。
适配器只保留应用上下文或弱会话引用，不保留 Activity / View。

syncBackground(PendingOnly/Privileged) 要求先成功前台 check。前者只调用 core.flush 和
pull_background，不读快照；后者先请求已授权平台快照并在变化时调用 core.record_local。
失败快照不会清除已有候选。最近一次观察只在会话内存中，成功平台写入也更新它，避免
轮询重复生成复制事件或回传自身写入；不改变协议去重、UUID、RCS1 或 core 重试。
前台显式发送仍可重新发送相同内容。所有网络/存储/冲突处理均来自 core。

Shizuku RPC 在独立工作线程等待 1.5 秒，主线程只收元数据唤醒；Binder 断开、拒绝、撤销、
锁屏、超限均显示不可用并降级已有事务续传。绑定非 daemon 来源仅在后台运行、发送方向、
应用在后台时存在；回前台、方向切换和关闭立即 unbind/remove。Android 普通前台服务不
赋予后台读取权限；iOS 未接入该来源。详情见 BACKGROUND.md。

前台接收保持网络前后快照与 generation 检查；后台接收由 core 的对应路径处理，保留
主机 generation 校验，但不能保证与其他应用新复制做快照比较。服务每 30 秒或变化唤醒
调度一个串行 tick，不定义第二套重试计时。仅接收清除旧发送候选；已提交内容不能撤回。

取消一个等待中的 Kotlin coroutine / Swift Task / foreign future **不保证撤销已入队命令**，
因为它可能已进入 core 事务。生命周期必须明确调用 requestClose；UI 用 epoch 检查丢弃
过期结果。平台包装类串行等待旧 shutdown 完成后才可打开同一状态存储，避免两个 Session
同时写同一文件。不同包装类不得共享同一 store；首版单窗口界面只创建一个 owner。

## 关闭和 panic

requestClose 是幂等的非阻塞请求：拒绝新命令、递增 generation、唤醒执行线程。
在途 core 操作可能完成事务落盘，排队命令与在途迟到结果返回 Closed；不再启动剪贴板调用。
shutdown 是可重复、可并发 await 的共享完成 future，在线程释放 core 与平台适配器后完成；
不在调用线程执行 Thread.join / Worker.join。Drop 只作为补充发出关闭请求。

每次 HTTP 请求由 core 设有限超时（普通请求 10 秒；changes 长轮询 30 秒，当前应用不使用）。
方向握手包含有截止时间的 WSS 操作及 HTTP 降级；失败重试同一会话／版本，不由 facade 另起协议会话。
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
WhenUnlockedThisDeviceOnly、不可同步 Keychain 条目。iOS 明文账户密码只留在会话内存。
Android 另用独立 Keystore AES-256-GCM 密钥和 AtomicFile 保存连接凭据，放在
noBackupFilesDir/connection，不改动 RCS1、配对 key 或收件箱。连接校验成功后保存，
应用级 controller 异步恢复字段；不写入普通设置或 SavedState Bundle，不自动连接。
读取失败不覆盖或抹除，必须显式删除连接配置；关闭会话保留配置，删除凭据不撤销令牌。
core 状态的临时文件写入、同步、原子 rename 和目录同步后才报告成功。
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
在 Disabled 策略下，core 将被禁止的本地敏感快照视为不可用正文，普通接收仍可继续；
新的远端标记消息继续过滤。测试保留本地敏感标记，确认未标记六位文本仍按普通内容接收，
不会为了继续同步而先要求用户清空剪贴板，也不使用密码格式猜测。


## 通知卡片可见性与接收解耦

通知卡片按当前 inbox 元数据协调，不依赖“本轮刚收到新 ID”。重新连接载入已有收件箱后，
监听服务运行、策略为 EncryptedInbox 且频道授权时恢复卡片；回到前台只 mask，不删除。
用户主动划掉后，同一消息不会随每次轮询重发，可通过“显示收件箱通知”显式恢复。
通知可用性允许应用前台查看，后台接收仍保留 `foreground` 检查，不在前台自动轮询。

卡片使用独立 group key，避免与 FGS 状态自动合组；原生操作按钮在展开态显示。通知正文
仍只在显式 Show 时读取，Copy 的 item / ticket / version / 权限及解锁检查保持不变。
验证通过实际 SystemUI 层级和 adb 输入点击；CLI 1.1 返回底层应用窗口时，使用 SDK
UiAutomator 的当前窗口 XML 回退，并保存原始层级。此路径不触碰真实剪贴板。

## 同步方向、过滤与设备身份

0.1.3 增加 `updateDirection`、`updateFilter`、`FilterConfig`、`TextFilterMode`、`SyncDirection`
和 `SessionStatus.deviceId`，同一 UniFFI facade 重新生成双端绑定，不手改生成代码。
方向在执行线程上拦截相反操作。Download 撤销上传候选，Retry 先委托 core.record_clear
再 flush，因此只撤销／完成确认；不会把已有上传偷偷重发。已开始提交的操作不能撤回。
Android 在配置成功后保存非敏感偏好，不改 RCS1。

过滤仅做有界输入转换（32 条、每条 512 字节），core::Filter 编译及判定。无效配置返回
InvalidInput，保留原规则；敏感策略更新保留当前过滤器。过滤配置变化通过 core.record_clear
撤销旧本地候选。`testFilter` 同步 API 必须在后台线程调用，限制样例 8192 字节，不记录样例。
UUID 直接从 core.persisted.state.device_id 返回，不返回 token、密码或密钥。

Android 对没有本应用写入 origin 的本地文本使用系统 ClipDescription.timestamp 观察标记，
前台与 Shizuku 来源一致；同一快照不会重记，用户再次复制相同文本仍可成为新事件。
此标记只用于平台事件观察，不改变 core 的消息 UUID 或协议。

## 暂停接口

MobileSession.set_paused(bool) 是新增的 UniFFI 方法，原有 DTO 和方向接口不变。
暂停先使在途 clipboard generation 失效，执行线程阻止发送、接收和重试、背景捕获；
core 上报现有方向及 paused，清理本地候选，保留配对和加密收件箱。方向变更不会解除暂停。
暂停上报失败时本地仍阻断，恢复须确认成功后才能放行；暂停时的 check 不读取剪贴板。
Android 在 sync-settings 保存暂停选择，并在 native 会话访问数据前恢复；平台写入时
另有 pause guard，显式的现有收件项复制使用 ID/epoch/focus 授权，不作为自动接收。
iOS 可调用共享 API，本次不增加 iOS UI 或发布 iOS 构建。
