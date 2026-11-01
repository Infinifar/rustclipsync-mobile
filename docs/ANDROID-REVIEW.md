# Android 0.1.2 用户验收

设备：moto razr 60 u / Android 16 / MyUI。按用户要求，本轮视觉和真机检查交由用户。

1. 通知状态栏图标应为剪贴板轮廓，不是白色方块。
2. 解锁后展开有消息的收件箱通知，点“显示”：显示正文、按钮变“隐藏”，通知栏保持展开。
3. 点“隐藏”：恢复 **，通知栏保持展开；可多次切换。
4. 点“复制”：收起通知栏，全文复制并保留敏感标记；后台自动写入关闭时也可显式复制。
5. 锁屏/熄屏后预览仍隐藏；锁屏上的显示请求不泄露正文，先解锁再操作。
6. 设置中切换跟随系统、浅色、深色，重启应用后保留选择；跟随系统时响应系统明暗变化。
7. 展开/折叠、窄窗口、大字体、键盘弹出时，连接表单和按钮可滚动访问；三页职责清楚。

本轮仅执行源码静态检查与 GitHub 平台构建，不复用旧截图宣称新 UI 已视觉通过。
开发签名仍未固定，签名冲突时不要直接删除用户数据，详见 BUILD.md。

官方依据：

- [Android 16 SystemUI 点击处理源码](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/packages/SystemUI/src/com/android/systemui/statusbar/phone/StatusBarRemoteInputCallback.java)。
- [Notification.Action.Builder 认证标志](https://developer.android.com/reference/android/app/Notification.Action.Builder#setAuthenticationRequired(boolean))。
- [Compose Material 3 明暗和动态色](https://developer.android.com/develop/ui/compose/designsystems/material3)。
