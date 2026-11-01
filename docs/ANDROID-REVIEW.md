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

## 0.1.3 增补

- 通知副标题已移除；隐藏星号随屏幕宽度／字体变化，约占卡片宽度 2/3，与真实正文长度无关。
- 依次选双向、仅发送、仅接收，检查前台按钮和后台接收授权；仅发送不接收，切仅接收后旧上传不会重发。
- 过滤页测试关闭、黑名单、白名单与“同时过滤接收”；无效正则保存失败，旧配置保留；规则不会回溯清空已保存的收件项。
- 同步页 UUID 可选中，重连后保持不变；无需展示任何 token。
- 外观页选中文／English／跟随系统，检查三页、提示、通知及系统应用语言入口，重启后保持。
- 关于页检查更新及项目地址。私有仓库／未发布提示应区别于“暂无更高版本”；不会自动安装更新。
