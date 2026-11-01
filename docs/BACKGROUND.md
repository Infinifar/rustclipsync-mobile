# 后台接收与通知

Android 实现两个独立默认关闭的开关：后台监听和后台写入。只在前台、已连接的会话中
启动 `dataSync` 前台服务，不从开机广播或进程重启自动连接。30 秒是两次接收检查之间的
调度间隔，不是实时到达保证；请求时长、Doze、断网和厂商限制可能进一步延迟。

共享 facade 新增 `receive_inbox` 和 `receive_background(write_clipboard)`，全部提交给原有
串行执行线程。core 新增 `pull_inbox` / `pull_background`，复用同一个接收验证、应用日志、
RCS1 提交、确认、过滤与敏感策略路径。后台不读剪贴板；无写入授权时只保存已标记的
加密收件箱，其他消息等待前台。待上传事务及重启校验仍要求在前台处理，不绕过冲突或
自动重试上传。不访问剪贴板的后台模式仍检查主机提供的 generation。

后台写入需显式确认。Android 的读取限制要求焦点，写入可以没有焦点；适配器分别检查
前台读取权限和“后台服务运行 + 后台写入已启用”的写入权限。后台无法比较当前剪贴板
快照，因此不能保证保留其他应用刚复制的内容；UI 明示可能被覆盖。敏感标记照常保留，
后台写入开关不改变跳过、加密收件箱或敏感自动写入策略。系统拒绝写入时沿用 core 的
模糊副作用恢复规则，记录 skipped，不无限重放。

加密收件箱通知只有四项条件同时满足才发布：收件箱策略、后台监听服务运行、通知及
频道授权、当前收件箱有消息。默认 `**`，两个操作为“显示 / 隐藏”和“复制”。显示时才向
facade 请求当前消息正文，最多向系统通知交付 2,000 字符；复制仍取完整正文。

服务不导出。按钮使用显式、不可变 PendingIntent，只携带当前消息 ID
与随机通知票据；没有正文、账号或密码。新消息更换票据。显示 / 复制要求解锁，公开锁屏
版本永远为 `**`，熄屏恢复隐藏，旧异步读取通过版本检查失效。复制通过私有服务发起，只为匹配的消息开启一次写入授权，
在主线程实际写入前重新检查当前票据、会话、通知授权和解锁状态；不切换界面。回前台只隐藏正文，保留卡片；关闭时移除通知。
重新连接或启用监听时从已保存的 inbox 元数据恢复卡片，不等待下一条消息。用户划掉的
卡片不会每次轮询重发，可在应用内主动“显示收件箱通知”。收件箱为空时解释其标记要求。

停止监听、关闭会话和超时均先撤销权限、预览和 native 命令入口，再异步等待 core 退出。
UI 不等待线程 join。服务为 START_NOT_STICKY；划掉任务主动停止，不保留登录凭据。

iOS 目前保持前台手动同步。后续需要 APNs 服务端、设备令牌管理和受系统调度的后台接收；
有限后台任务不等于无限监听，不通过音频、定位或其他不匹配的后台模式维持会话。
当前未在 macOS / Xcode 编译或真机验证本功能。

官方依据（实施时核对）：

- [ClipboardManager：读取需要输入焦点，setPrimaryClip 写入](https://developer.android.com/reference/android/content/ClipboardManager)。
- [Android 16 ClipboardService：OP_WRITE_CLIPBOARD 分支明确允许无焦点写入](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/clipboard/ClipboardService.java)。
- [复制与敏感标记](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)。
- [前台服务类型与 dataSync 权限](https://developer.android.com/develop/background-work/services/fgs/service-types)。
- [Android 15+ 前台服务超时及测试](https://developer.android.com/develop/background-work/services/fgs/timeout)。
- [POST_NOTIFICATIONS：拒绝授权不禁止启动 FGS，但不在通知栏展示](https://developer.android.com/develop/ui/compose/notifications/notification-permission)。
- [通知操作与直接 Activity PendingIntent](https://developer.android.com/develop/ui/compose/notifications/create-notification)。
- [Apple 后台策略与有限运行时间](https://developer.apple.com/documentation/backgroundtasks/choosing-background-strategies-for-your-app)。

core 来源固定为 `1a8ec6b97cc925fcbbbf51f9b8c8e31f97f1ea4e`，在 core 仓库
`codex/mobile-background-inbox` 分支开发；未推送 core 的公共上游。mobile 包含该提交的完整
校验快照，干净克隆通过 Cargo source replacement 构建，不需要相邻仓库或取得该 Git 提交。
重新导出尚未公开的提交需使用包含该对象的 core 克隆；此步骤属于维护，不是构建前提。
