# Shizuku / Android 权限边界静态复核

- 私有 BackgroundSyncService exported=false；通知使用显式不可变 PendingIntent，ID/ticket/version 校验与解锁检查不移除。
- Shizuku Provider exported=true 是 SDK 规定入口，以平台 INTERACT_ACROSS_USERS_FULL 权限保护；不导出通用执行服务。
- snapshot/observe 先核对 Binder.getCallingUid == context.applicationInfo.uid，snapshot 读取时再 clearCallingIdentity；destroy 只允许 owner、shell、root/本进程管理关闭。
- 来源限定 shell UID 2000。公开包名 com.android.shell 与真实调用 UID 对应；不假定 root 可冒用该包，不将其失败当空剪贴板。
- 每次 RPC 前后重新核对 Shizuku 授权和同一服务绑定；回调只收 shell 唤醒，停止/切方向/前台/关闭时 unbind(remove=true)。
- 设备锁定、熄屏和会话撤销都检查；RPC 在工作线程有界等待，不占 UI 网络线程、不等待 join。
- AIDL 不含凭据、密钥或任意命令；UserService 不联网/不落盘，指纹仅在内存，通知 Intent 不携带正文。
- POST_NOTIFICATIONS 沿用显式请求、rationale/永久拒绝进入设置；不自动反复请求。
- 上述为源码复核，未验证真实 Shizuku、厂商 ROM、权限撤销或 Binder 隔离运行。

官方依据：Shizuku-API README（13.1.5、UserService Context/非 daemon/销毁事务）、Android 16 IClipboard 与 ClipboardService 的签名、后台权限及 UID/包所有权检查。链接见 docs/BACKGROUND.md。
