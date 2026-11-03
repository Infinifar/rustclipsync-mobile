# 后台运行、Shizuku 与加密收件箱通知

Android 0.1.4 使用一个默认关闭的“后台运行”开关。它只决定离开应用后是否继续当前
会话；双向 / 仅发送 / 仅接收统一控制前后台方向，不再另设后台写入开关。
启用前确认普通接收可能覆盖剪贴板。旧版后台监听设置不会自动升级为更宽的授权，
升级后需重新开启；同步方向、过滤、语言保留，敏感策略从本版起持久保存。

| 方向 | 后台发送 | 后台接收 |
| --- | --- | --- |
| 双向 | 新内容需 Shizuku；已取得内容可续传 | 普通文本写入；敏感内容按策略处理 |
| 仅发送 | 同上 | 不接收，不产生新收件箱消息 |
| 仅接收 | 不读取、不推送；通过 core 清除旧上传候选 | 普通文本写入；敏感内容按策略处理 |

敏感处理三选一，与方向及运行时段独立：跳过已标记敏感消息、保存到加密收件箱、
直接写入剪贴板（不进收件箱）。普通消息不需要第二个写入开关。只有明确敏感标记有效，
不承诺自动识别未标记的密码、验证码或其他文本。规则过滤仍由 core 处理。

## 后台剪贴板来源

用户安装并启动 Shizuku 13+（无线调试 / ADB shell 模式），在应用设置中显式“请求 Shizuku 授权”。SDK API/provider
固定 13.1.5。拒绝不反复弹窗；可在 Shizuku 管理中重新授权。连接/授权/读取不可用状态
在设置展示。授权状态与来源状态分开展示；授权结果按请求码匹配，成功后立即更新，
来源空闲不会覆盖授权成功。拒绝后指引 Shizuku 应用管理，不反复弹窗。Shizuku 不可用时不伪造快照，不取消已有上传，仍可续传应用已取得内容并接收。

仅在后台运行、会话已连接、方向包含发送且应用离开前台时绑定非 daemon UserService。
设备锁定或熄屏不读取；切仅接收、回前台、停止、关闭、超时和进程退出均撤销来源。
UserService 每秒检查一次本机剪贴板变化，只通过 oneway 回调发送无正文的唤醒信号；
该频率不保证捕获每一次快速复制。实际快照由 Rust 工作者请求并送回同一个 core Session。
后台 RPC 文本上限 256 KiB，超过时报告不可用，不截断发送；前台仍沿用 core 上限。

UserService 只有 snapshot/observe/destroy 三个方法，所有入口核对 Binder UID；
不运行任意命令、不持有账号、状态密钥、不联网、不落盘。Shizuku Provider 按官方契约用
INTERACT_ACROSS_USERS_FULL 保护；普通应用不能通过该 Provider 获取接口。
隐藏 IClipboard 的反射仅在 Shizuku 进程内，按方法签名适配 API 31/新版 deviceId 参数，
无固定 transact 编号。当前仅支持 ADB shell UID 2000；root 模式明确提示改用 ADB，不将失败当作空剪贴板。
这是 AOSP 校验 UID 与调用包所有权的限制；不冒用身份或执行 su 命令。厂商兼容性仍需设备验证。

剪贴板 Binder RPC 在独立工作线程等待，1.5 秒后返回不可用，UI 不等待快照或线程退出。
接收写入仍经过可撤销的 MainThreadGate。每次读取前后核对授权及当前绑定，回调不持有
Activity/View；撤销后不向旧 UI 交付正文。平台只适配来源，不实现同步状态机。

## 调度与共享层

前台已连接会话显式启动 dataSync 前台服务，不从开机或进程重启自动连接。
服务每 30 秒调度一次，剪贴板变化合并唤醒，不以定时器重写 core 重试算法。
共享 sync_background(PendingOnly/Privileged) 在同一串行执行线程调用 core 的
record_local / record_clear / flush / pull_background；必须先完成前台 check。
只读模式不访问剪贴板；特权读取失败保留已有事务，接收仍按 core 状态允许的条件继续。

会话只记住本地剪贴板的最近一次观察，避免把未变化快照重复作为新复制事件；成功 OS
接收/显式复制同样更新观察，避免晚到监听器形成回传。此内存记录随会话释放，不改变
RCS1，不生成新的协议去重或重试机制。前台显式“发送”仍允许用户重新发送相同文本。
上传 UUID、重试、取消、ack、过滤、apply journal、冲突与加密持久化全部由 core 负责。

## 收件箱通知

条件为：方向包含接收、选择加密收件箱、后台运行服务存活、通知及频道授权、收件箱
已有合格消息。只看到“后台运行”状态通知不等于收到敏感消息。收件箱页显示未满足条件、
实际保存策略、最近同步结果，以及接收过滤是否启用；可跳转同步设置或重新发布已有通知。
首次注册不重放旧消息，未标记或被规则拒绝的消息不进入收件箱。“直接写入”不会产生新
收件箱消息；旧收件箱项目保留但不随新策略自动显示通知。

默认星号与正文长度无关，按屏幕与字体估算约占卡片宽度 2/3；下方两个相邻的系统文本
操作为“显示/隐藏”和“复制”。显示/隐藏不请求关闭通知栏；复制走系统认证收起路径。
MyUI 最终交互由用户验收。显示仅读取当前项最多 2,000 字符；复制使用完整正文并保留
敏感标记。明文不进入 Intent、日志或状态事件，锁屏公开版本始终隐藏。

显式、不可变、私有服务 PendingIntent 携带当前 ID 和随机票据。新消息换票；熄屏、前台、
授权变化及关闭撤销预览；实际写入前复核票据、解锁和生命周期。重连可恢复磁盘中已有
收件项通知，划掉的卡片不每次轮询重发。拒绝通知不影响加密保存。

## 系统边界

Android 15+ dataSync 累计运行时限仍适用（每 24 小时 6 小时；回前台重置）；Shizuku
不解除服务时限。Doze、断网、厂商限制可能延迟服务，不承诺实时或无限常驻。
停止、划掉任务、超时立即撤销来源与 native 入口，再异步等待 core；UI 不 join。
服务 START_NOT_STICKY；划掉任务保留后台设置和仍有效的当前服务，系统超时停止当前会话但保留设置；系统杀死进程、强行停止或重启后仍需在前台重新连接。凭据独立加密保存。

iOS 仅移动端，当前保持前台手动同步，后续需要 APNs、令牌管理和受系统调度的接收。
不借用音频/定位保活；本功能没有 Xcode 编译或 iOS 真机结论。

官方依据（实施时核对）：

- [Shizuku SDK、授权和 UserService 官方说明](https://github.com/RikkaApps/Shizuku-API)。
- [Android 16 IClipboard 参数契约](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/content/IClipboard.aidl)。
- [ClipboardManager：读取需要输入焦点，setPrimaryClip 写入](https://developer.android.com/reference/android/content/ClipboardManager)。
- [Android 16 ClipboardService：OP_WRITE_CLIPBOARD 分支明确允许无焦点写入](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/clipboard/ClipboardService.java)。
- [复制与敏感标记](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)。
- [前台服务类型与 dataSync 权限](https://developer.android.com/develop/background-work/services/fgs/service-types)。
- [Android 15+ 前台服务超时及测试](https://developer.android.com/develop/background-work/services/fgs/timeout)。
- [POST_NOTIFICATIONS：拒绝授权不禁止启动 FGS，但不在通知栏展示](https://developer.android.com/develop/ui/compose/notifications/notification-permission)。
- [通知操作与直接 Activity PendingIntent](https://developer.android.com/develop/ui/compose/notifications/create-notification)。
- [原生操作在展开通知中显示](https://developer.android.com/reference/android/app/Notification.Builder#addAction(android.app.Notification.Action))。
- [显式分组与系统自动分组](https://developer.android.com/develop/ui/views/notifications/group)。
- [Apple 后台策略与有限运行时间](https://developer.apple.com/documentation/backgroundtasks/choosing-background-strategies-for-your-app)。

core 来源固定为 `1a8ec6b97cc925fcbbbf51f9b8c8e31f97f1ea4e`，在 core 仓库
`codex/mobile-background-inbox` 分支开发；未推送 core 的公共上游。mobile 包含该提交的完整
校验快照，干净克隆通过 Cargo source replacement 构建，不需要相邻仓库或取得该 Git 提交。
重新导出尚未公开的提交需使用包含该对象的 core 克隆；此步骤属于维护，不是构建前提。

Android 对没有本应用写入 origin 的本地文本使用系统 ClipDescription.timestamp 观察标记，
前台与 Shizuku 来源一致；同一快照不会重记，用户再次复制相同文本仍可成为新事件。
此标记只用于平台事件观察，不改变 core 的消息 UUID 或协议。

### 后台生命周期与接收调度修复

划掉最近任务不再把后台开关永久写成关闭，系统允许时保留已连接的服务；系统超时
只停止本次会话并保留开关，不循环重启。进程被杀、强行停止或重启后仍需前台重新连接。
开启后台运行且连接成功后，App 前台有焦点时也自动接收；前台发送仍使用发送按钮。
服务每个已完成的 tick 后等待 3 秒，网络请求时间另计，不保证固定延迟。
连接结束和窗口重新获得焦点会唤醒接收。Shizuku 授权与服务端连接是独立条件。

遇到旧上传冲突或超过重试期限时，后台同步与重试入口调用 core 的持久化安全跳过：
取消旧上传及当时排队项、保留系统剪贴板、跳过当时远端消息，继续处理后续新消息。
恢复后的复制来源先建立观察基线，避免把保留的旧剪贴板再次作为新上传。
普通网络失败仍保留事务；存储、身份和 epoch 错误不会被伪装成恢复成功。
“有待处理的事务”也可能只是等待确认，不等于已确定冲突；前台接收循环会先处理待办。
