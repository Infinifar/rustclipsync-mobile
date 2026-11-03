# Best Practices and Security Alignment Update: encrypted inbox notification availability

- **Security alignment area:** Revocable immutable PendingIntents and private notification previews.
- **Priority:** Medium; restore missing controls while keeping plaintext behind an explicit unlocked action.
- **Scope:** InboxNotifications, SessionController, MainActivity; version/build identification and notification regression tests. No new permissions, exported components, or dependencies.
- **Implementation:** Coordinate notification availability from durable inbox metadata while the listener runs. Foreground entry masks the body and retains the card. Dismissal suppresses repeated publication of the same item until an explicit repost. A dedicated group excludes automatic grouping with the listener status notification. UI explains empty inbox, policy and authorization, and provides manual repost.
- **Existing security boundary:** Explicit immutable private-service PendingIntents contain only current item ID and random ticket. Show/Copy require authorization and unlocking. Epoch, item, ticket and preview version invalidate stale reads and copy permits. Body stays out of Intent extras, status messages and logs; notification plaintext is handed to SystemUI only after Show. Copy keeps the sensitive mark and never enables general background writes. Stop/policy/close still revoke admission before asynchronous native shutdown.
- **Validation:** CI292 compiled both ABIs, ordinary/disposable APKs and lint, and passed fmt/Clippy, 12 Rust tests, rustdoc, route and host FFI checks. API35 complete suite passed ten ordinary synthetic smoke tests and six real isolated-server methods. Actual SystemUI Show, Hide and Copy were clicked; screenshots show the independent card and both body states. API31 also passed ten smoke tests and five complete methods, including actual Show/Hide/Copy after fixing the native expander selector. Both GitHub build/check jobs passed independently; runtime evidence remains tied to CI292. All clipboard/storage data is synthetic; no physical-device, real clipboard/Keystore or iOS result is implied.

## Unified diff

```diff
diff --git a/android/app/src/main/java/io/github/infinifar/rustclipsync/InboxNotifications.kt b/android/app/src/main/java/io/github/infinifar/rustclipsync/InboxNotifications.kt
index c43d9b1..82a05b9 100644
--- a/android/app/src/main/java/io/github/infinifar/rustclipsync/InboxNotifications.kt
+++ b/android/app/src/main/java/io/github/infinifar/rustclipsync/InboxNotifications.kt
@@ -26,6 +26,7 @@ internal class InboxNotifications(
     private var ticket: String? = null
     private var body: String? = null
     private var version = 0L
+    private var dismissedItem: String? = null
     private val pending = mutableSetOf<PendingIntent>()
 
     init { channels(context) }
@@ -41,6 +42,7 @@ internal class InboxNotifications(
     fun clear() {
         ++version
         body = null; item = null; ticket = null
+        dismissedItem = null
         pending.forEach { it.cancel() }; pending.clear()
         manager.cancel(INBOX_ID)
     }
@@ -53,10 +55,17 @@ internal class InboxNotifications(
 
     fun refreshAuthorization() { if (!authorized()) clear() }
 
+    /** Restore durable inbox availability without treating every poll as a new message. */
+    fun sync(id: String?, eligible: Boolean) {
+        if (!eligible || id == null || !authorized()) { clear(); return }
+        if (item == id || dismissedItem == id) return
+        offer(id)
+    }
+
     suspend fun action(intent: Intent, eligible: () -> Boolean, read: suspend (String) -> String?) {
         val id = matchingItem(intent) ?: return
         if (!eligible() || !authorized()) { clear(); return }
-        if (intent.action == DISMISS) { clear(); return }
+        if (intent.action == DISMISS) { clear(); dismissedItem = id; return }
         if (intent.action == HIDE) { mask(); return }
         if (intent.action != SHOW || locked()) return
         val revision = ++version
@@ -101,6 +110,9 @@ internal class InboxNotifications(
         val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS)
         val notification = Notification.Builder(context, INBOX_CHANNEL)
             .setSmallIcon(R.drawable.ic_launcher).setContentTitle("加密收件箱")
+            .setSubText("展开可显示／隐藏、复制")
+            // Keep this single card out of the automatic group with the FGS status notification.
+            .setGroup("encrypted-inbox-card")
             .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
             .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
             .setLocalOnly(true).setOnlyAlertOnce(true).setContentIntent(open)
diff --git a/android/app/src/main/java/io/github/infinifar/rustclipsync/MainActivity.kt b/android/app/src/main/java/io/github/infinifar/rustclipsync/MainActivity.kt
index 210b7c9..18ebab5 100644
--- a/android/app/src/main/java/io/github/infinifar/rustclipsync/MainActivity.kt
+++ b/android/app/src/main/java/io/github/infinifar/rustclipsync/MainActivity.kt
@@ -72,6 +72,7 @@ private fun SyncScreen(session: SessionController) {
         Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
             Text("RustClipSync", style = MaterialTheme.typography.headlineLarge)
             Text("Android 独立客户端 · 手动同步与可选后台收件箱")
+            Text("开发构建 ${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_REVISION}", style = MaterialTheme.typography.labelSmall)
             OutlinedTextField(server, { server = it }, label = { Text("HTTPS 服务器地址") }, enabled = editable, singleLine = true)
             OutlinedTextField(username, { username = it }, label = { Text("账户") }, enabled = editable, singleLine = true)
             OutlinedTextField(password, { password = it }, label = { Text("密码（仅本次内存会话）") }, enabled = editable, singleLine = true, visualTransformation = PasswordVisualTransformation())
@@ -105,6 +106,8 @@ private fun SyncScreen(session: SessionController) {
                 Text(if (session.notificationAuthorized) "收件箱通知默认 **；可显示／隐藏、复制" else "通知未授权或频道关闭：继续保存收件箱，不展示消息通知")
                 if (!session.notificationAuthorized) TextButton(onClick = ::requestNotificationPermission) { Text("允许收件箱通知") }
                 TextButton(onClick = ::notificationSettings) { Text("通知权限与频道设置") }
+                Text(session.inboxNotificationHint)
+                OutlinedButton(onClick = session::showInboxNotification, enabled = session.inboxNotificationReady && !session.busy) { Text("显示收件箱通知") }
             }
             if (session.connected) {
                 Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
diff --git a/android/app/src/main/java/io/github/infinifar/rustclipsync/SessionController.kt b/android/app/src/main/java/io/github/infinifar/rustclipsync/SessionController.kt
index aeb5faf..4dd270a 100644
--- a/android/app/src/main/java/io/github/infinifar/rustclipsync/SessionController.kt
+++ b/android/app/src/main/java/io/github/infinifar/rustclipsync/SessionController.kt
@@ -82,10 +82,11 @@ internal class SessionController(
         foreground = value
         updateWriteAccess()
         native?.clipboardChanged() // Invalidate an in-flight receive across a lifecycle transition.
-        if (value) { notifications.clear(); refreshNotificationAuthorization(); startBackgroundIfEnabled() }
+        if (value) { notifications.mask(); refreshNotificationAuthorization(); startBackgroundIfEnabled() }
         else {
             clipboardFocus(false)
             if (!backgroundEnabled || (!backgroundRunning && !startingService)) close()
+            else syncNotification()
         }
     }
 
@@ -135,6 +136,7 @@ internal class SessionController(
         startingService = false
         backgroundRunning = backgroundEnabled && connected
         updateWriteAccess()
+        syncNotification()
         return backgroundRunning
     }
 
@@ -150,11 +152,29 @@ internal class SessionController(
         message = "后台监听已达到系统时限并停止；可回到前台重新启用"
     }
 
-    fun notificationEligible(): Boolean = backgroundEnabled && backgroundRunning && connected && !foreground && selectedPolicy == SensitivePolicy.ENCRYPTED_INBOX
+    fun notificationEligible(): Boolean = backgroundEnabled && backgroundRunning && connected && selectedPolicy == SensitivePolicy.ENCRYPTED_INBOX
+
+    val inboxNotificationReady: Boolean get() = notificationEligible() && notificationAuthorized && status?.inbox != null
+    val inboxNotificationHint: String get() = when {
+        !connected -> "连接后可显示加密收件箱通知"
+        !backgroundEnabled || !backgroundRunning -> "请先启用后台监听并等待服务启动"
+        selectedPolicy != SensitivePolicy.ENCRYPTED_INBOX -> "当前未选择加密收件箱策略"
+        status?.inbox == null -> "收件箱为空：等待已明确标记的敏感消息；普通文本不会自动进入收件箱"
+        !notificationAuthorized -> "收件箱已有消息，但通知权限或频道未开启"
+        else -> "收件箱已有消息；展开通知栏的“加密收件箱”可显示／隐藏、复制"
+    }
+
+    private fun syncNotification() = notifications.sync(status?.inbox?.id, notificationEligible())
+    fun showInboxNotification() {
+        refreshNotificationAuthorization()
+        val id = status?.inbox?.id ?: return
+        if (inboxNotificationReady) notifications.offer(id)
+    }
 
     fun refreshNotificationAuthorization() {
         notificationAuthorized = InboxNotifications.authorized(application)
         notifications.refreshAuthorization()
+        syncNotification()
     }
 
     /** A scheduling tick, not an upload retry loop. Core owns all receive/ack rules. */
@@ -162,10 +182,7 @@ internal class SessionController(
         refreshNotificationAuthorization()
         if (!backgroundEnabled || !backgroundRunning || !connected || foreground || busy) return
         if (!backgroundWritesEnabled && selectedPolicy != SensitivePolicy.ENCRYPTED_INBOX) return
-        val previous = status?.inbox?.id
         perform { receiveBackground(backgroundWritesEnabled); "后台接收已检查" }
-        val id = status?.inbox?.id
-        if (notificationEligible() && id != null && id != previous) notifications.offer(id)
     }
 
     suspend fun notificationAction(intent: Intent) = notifications.action(intent, ::notificationEligible) { id ->
@@ -221,7 +238,7 @@ internal class SessionController(
                 }
                 message = safeError(error)
             }
-        } finally { if (version == epoch) busy = false }
+        } finally { if (version == epoch) { busy = false; syncNotification() } }
     }
 
     fun close() {
```
