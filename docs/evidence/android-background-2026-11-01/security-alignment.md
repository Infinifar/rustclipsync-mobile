# Best Practices and Security Alignment Update

- **Security alignment area:** 私有组件、不可变 PendingIntent、敏感通知预览、权限和生命周期撤销。
- **Impact and priority:** High；避免外部组件触发复制、旧按钮操作新消息、锁屏正文泄露、关闭后执行迟到写入。
- **Scope of changes:** AndroidManifest.xml；SessionPlatform / AndroidPlatform、SessionController、SyncApplication、MainActivity；新增 BackgroundSyncService、InboxNotifications。测试修改 NotificationSafetyTest、ServiceIntegrationTest。未新增依赖。
- **Implementation summary:** service 不导出；显式不可变 PendingIntent 仅含消息 ID 和随机票据，替换消息与关闭时主动取消旧票据。仅在显式显示、设备解锁且当前消息有效时读取预览；公开版本 **。熄屏、前台、权限 / 频道变化、关闭或新消息撤销预览。复制由私有服务直接完成，临时写入授权绑定消息及预览版本；主线程实际写入前重新检查票据、会话、解锁及通知授权，完成后隐藏。自动后台写入另行确认；后台从不读取剪贴板。POST_NOTIFICATIONS 每次操作动态检查，拒绝或频道关闭可从设置恢复。前台服务超时和关闭不等待线程退出。
- **Code under validation:** mobile fb4bcaf8a5726047c7c74186f9e65163a7f3e106；core 1a8ec6b97cc925fcbbbf51f9b8c8e31f97f1ea4e。
- **Validation:** core 49 项、facade 12 项合成测试及 fmt / Clippy 通过。GitLab #125 / #284 完整编译通过，API 31 / 35 各 9 项 smoke，完整服务方法分别 4 / 5 项通过（含后台复制与 API 35 超时）；最终范围见 [VALIDATION.md](../../VALIDATION.md)；不代表真机、真实剪贴板 / Keystore 或 iOS 编译验证。
- **Skills:** [android-intent-security](/home/cassius/.codex/skills/android-intent-security/SKILL.md) 与 [android-permissions-security](/home/cassius/.codex/skills/android-permissions-security/SKILL.md)。

## Implementation diff

```diff
diff --git a/android/app/src/main/AndroidManifest.xml b/android/app/src/main/AndroidManifest.xml
index 4e90bee..95b4708 100644
--- a/android/app/src/main/AndroidManifest.xml
+++ b/android/app/src/main/AndroidManifest.xml
@@ -1,5 +1,8 @@
 <manifest xmlns:android="http://schemas.android.com/apk/res/android">
     <uses-permission android:name="android.permission.INTERNET" />
+    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
+    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
+    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
     <application android:name=".SyncApplication" android:label="RustClipSync" android:allowBackup="false"
         android:dataExtractionRules="@xml/data_extraction_rules" android:icon="@drawable/ic_launcher"
         android:usesCleartextTraffic="false" android:theme="@android:style/Theme.Material.Light.NoActionBar">
@@ -9,5 +12,6 @@
                 <category android:name="android.intent.category.LAUNCHER" />
             </intent-filter>
         </activity>
+        <service android:name=".BackgroundSyncService" android:exported="false" android:foregroundServiceType="dataSync" />
     </application>
 </manifest>
diff --git a/android/app/src/main/java/io/github/infinifar/rustclipsync/AndroidPlatform.kt b/android/app/src/main/java/io/github/infinifar/rustclipsync/AndroidPlatform.kt
index c046cea..92e19be 100644
--- a/android/app/src/main/java/io/github/infinifar/rustclipsync/AndroidPlatform.kt
+++ b/android/app/src/main/java/io/github/infinifar/rustclipsync/AndroidPlatform.kt
@@ -10,25 +10,43 @@ import io.github.infinifar.rustclipsync.bindings.Platform
 import io.github.infinifar.rustclipsync.bindings.MobileSession
 import java.lang.ref.WeakReference
 
-internal class AndroidPlatform(context: Context, private val state: SecureState) : Platform {
+internal interface SessionPlatform : Platform {
+    fun observe(session: MobileSession)
+    fun focus(value: Boolean)
+    fun backgroundWrite(value: Boolean)
+    fun explicitCopy(id: String?, permitted: () -> Boolean)
+    fun revoke()
+}
+
+internal class AndroidPlatform(context: Context, private val state: SecureState) : SessionPlatform {
     private val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
     private val gate = MainThreadGate()
+    // Main-thread only. A foreground service does not grant clipboard access.
+    private var focused = false
+    private var allowBackgroundWrite = false
+    private var copyId: String? = null
+    private var copyPermitted: (() -> Boolean)? = null
     private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
 
     // Called on the UI thread; listener sends a counter only, never reads clipboard content.
-    fun observe(session: MobileSession) {
+    override fun observe(session: MobileSession) {
         val weakSession = WeakReference(session)
         listener = ClipboardManager.OnPrimaryClipChangedListener {
             if (gate.isActive) weakSession.get()?.takeUnless { it.uniffiIsDestroyed }?.clipboardChanged()
         }
         clipboard.addPrimaryClipChangedListener(listener!!)
     }
-    fun revoke() {
+    override fun revoke() {
         gate.revoke()
+        copyId = null; copyPermitted = null; allowBackgroundWrite = false; focused = false
         listener?.let { clipboard.removePrimaryClipChangedListener(it) }
         listener = null
     }
+    override fun focus(value: Boolean) { focused = value }
+    override fun backgroundWrite(value: Boolean) { allowBackgroundWrite = value }
+    override fun explicitCopy(id: String?, permitted: () -> Boolean) { copyId = id; copyPermitted = if (id == null) null else permitted }
     override fun clipboardSnapshot(): ClipboardText? = gate.call {
+        if (!focused) throw AdapterException.Unavailable()
         val clip = clipboard.primaryClip ?: return@call null
         if (clip.itemCount == 0) return@call null
         val value = clip.getItemAt(0).text?.toString() ?: return@call null
@@ -37,6 +55,9 @@ internal class AndroidPlatform(context: Context, private val state: SecureState)
         ClipboardText(value, extras?.getBoolean(SENSITIVE, false) ?: false, extras?.getString(ORIGIN))
     }
     override fun clipboardWrite(value: ClipboardText) = gate.call {
+        if (copyId != null) {
+            if (value.origin != copyId || !value.sensitive || copyPermitted?.invoke() != true) throw AdapterException.Unavailable()
+        } else if (!focused && !allowBackgroundWrite) throw AdapterException.Unavailable()
         val clip = ClipData.newPlainText("RustClipSync", value.text)
         clip.description.extras = PersistableBundle().apply {
             putBoolean(SENSITIVE, value.sensitive)
diff --git a/android/app/src/main/java/io/github/infinifar/rustclipsync/BackgroundSyncService.kt b/android/app/src/main/java/io/github/infinifar/rustclipsync/BackgroundSyncService.kt
new file mode 100644
index 0000000..e1d5dce
--- /dev/null
+++ b/android/app/src/main/java/io/github/infinifar/rustclipsync/BackgroundSyncService.kt
@@ -0,0 +1,96 @@
+package io.github.infinifar.rustclipsync
+
+import android.app.Service
+import android.app.NotificationManager
+import android.content.BroadcastReceiver
+import android.content.Context
+import android.content.Intent
+import android.content.IntentFilter
+import android.content.pm.ServiceInfo
+import android.os.Build
+import android.os.IBinder
+import kotlinx.coroutines.CoroutineScope
+import kotlinx.coroutines.Dispatchers
+import kotlinx.coroutines.Job
+import kotlinx.coroutines.SupervisorJob
+import kotlinx.coroutines.cancel
+import kotlinx.coroutines.delay
+import kotlinx.coroutines.isActive
+import kotlinx.coroutines.launch
+
+/** User-started, bounded dataSync lifetime. Never restarted or started at boot. */
+class BackgroundSyncService : Service() {
+    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
+    private val session get() = (application as SyncApplication).session
+    private var polling: Job? = null
+    private val privacyChanges = object : BroadcastReceiver() {
+        override fun onReceive(context: Context, intent: Intent) {
+            when (intent.action) {
+                Intent.ACTION_SCREEN_OFF -> session.notifications.mask()
+                NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED,
+                NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED -> {
+                    session.notifications.clear()
+                    session.refreshNotificationAuthorization()
+                }
+            }
+        }
+    }
+
+    override fun onCreate() {
+        super.onCreate()
+        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
+            addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
+            addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
+        } // Protected system broadcasts only; no caller-supplied extras are used.
+        if (Build.VERSION.SDK_INT >= 33) registerReceiver(privacyChanges, filter, Context.RECEIVER_NOT_EXPORTED)
+        else registerReceiver(privacyChanges, filter)
+    }
+
+    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
+        when (intent?.action) {
+            InboxNotifications.START -> {
+                try {
+                    startForeground(InboxNotifications.SERVICE_ID, InboxNotifications.serviceNotification(this, session.backgroundWritesEnabled), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
+                } catch (_: RuntimeException) { session.serviceStopped(); stopSelf(); return START_NOT_STICKY }
+                if (!session.serviceStarted()) { stopSelf(); return START_NOT_STICKY }
+                if (polling == null) polling = scope.launch {
+                    while (isActive && session.backgroundEnabled && session.connected) {
+                        session.pollInbox()
+                        // No wake lock, clipboard watcher, or platform-side upload/retry algorithm.
+                        delay(30_000)
+                    }
+                    stopSelf()
+                }
+            }
+            InboxNotifications.STOP -> { session.configureBackgroundListening(false); stopSelf() }
+            InboxNotifications.SHOW, InboxNotifications.HIDE, InboxNotifications.DISMISS -> {
+                if (session.backgroundRunning) scope.launch { session.notificationAction(intent) }
+                else stopSelf()
+            }
+            InboxNotifications.COPY -> {
+                if (session.backgroundRunning) scope.launch { session.notificationCopy(intent) }
+                else stopSelf()
+            }
+            else -> stopSelf()
+        }
+        return START_NOT_STICKY
+    }
+
+    override fun onTimeout(startId: Int, fgsType: Int) {
+        session.serviceTimedOut()
+        stopForeground(STOP_FOREGROUND_REMOVE)
+        stopSelf()
+    }
+    override fun onTaskRemoved(rootIntent: Intent?) {
+        session.configureBackgroundListening(false)
+        stopSelf()
+    }
+    override fun onDestroy() {
+        scope.cancel()
+        unregisterReceiver(privacyChanges)
+        session.serviceStopped()
+        stopForeground(STOP_FOREGROUND_REMOVE)
+        super.onDestroy()
+    }
+    override fun onBind(intent: Intent?): IBinder? = null
+}
diff --git a/android/app/src/main/java/io/github/infinifar/rustclipsync/InboxNotifications.kt b/android/app/src/main/java/io/github/infinifar/rustclipsync/InboxNotifications.kt
new file mode 100644
index 0000000..c43d9b1
--- /dev/null
+++ b/android/app/src/main/java/io/github/infinifar/rustclipsync/InboxNotifications.kt
@@ -0,0 +1,169 @@
+package io.github.infinifar.rustclipsync
+
+import android.Manifest
+import android.annotation.SuppressLint
+import android.app.KeyguardManager
+import android.app.Notification
+import android.app.NotificationChannel
+import android.app.NotificationManager
+import android.app.PendingIntent
+import android.content.Context
+import android.content.Intent
+import android.content.pm.PackageManager
+import android.net.Uri
+import android.os.Build
+import android.os.PowerManager
+import java.util.UUID
+import kotlinx.coroutines.CancellationException
+
+/** Plaintext is read only for an explicit, unlocked Show action; it is never placed in an Intent. */
+internal class InboxNotifications(
+    private val context: Context,
+    private val authorized: () -> Boolean = { InboxNotifications.authorized(context) },
+) {
+    private val manager = context.getSystemService(NotificationManager::class.java)
+    private var item: String? = null
+    private var ticket: String? = null
+    private var body: String? = null
+    private var version = 0L
+    private val pending = mutableSetOf<PendingIntent>()
+
+    init { channels(context) }
+
+    fun offer(id: String) {
+        clear()
+        if (!authorized()) return
+        item = id
+        ticket = UUID.randomUUID().toString()
+        publish()
+    }
+
+    fun clear() {
+        ++version
+        body = null; item = null; ticket = null
+        pending.forEach { it.cancel() }; pending.clear()
+        manager.cancel(INBOX_ID)
+    }
+
+    fun mask() {
+        ++version
+        body = null
+        if (item != null) publish()
+    }
+
+    fun refreshAuthorization() { if (!authorized()) clear() }
+
+    suspend fun action(intent: Intent, eligible: () -> Boolean, read: suspend (String) -> String?) {
+        val id = matchingItem(intent) ?: return
+        if (!eligible() || !authorized()) { clear(); return }
+        if (intent.action == DISMISS) { clear(); return }
+        if (intent.action == HIDE) { mask(); return }
+        if (intent.action != SHOW || locked()) return
+        val revision = ++version
+        val text = try { read(id) } catch (error: Exception) {
+            if (error is CancellationException) throw error
+            null
+        }
+        if (revision != version) return
+        if (!eligible() || !authorized() || locked() || text == null) { mask(); return }
+        // Bound notification IPC size. The Copy action still copies the complete inbox item.
+        body = text.take(2_000)
+        publish()
+    }
+
+    data class CopyPermit(val id: String, val version: Long)
+    fun claimCopy(intent: Intent): CopyPermit? =
+        if (intent.action == COPY && authorized() && !locked()) matchingItem(intent)?.let { CopyPermit(it, version) } else null
+
+    private fun matchingItem(intent: Intent): String? {
+        val id = item ?: return null
+        // Only scalar strings are accepted; no caller-provided Intent is forwarded.
+        return try {
+            id.takeIf { intent.getStringExtra(ITEM) == id && intent.getStringExtra(TICKET) == ticket }
+        } catch (_: RuntimeException) { null }
+    }
+
+    private fun locked(): Boolean {
+        val keyguard = context.getSystemService(KeyguardManager::class.java)
+        return keyguard.isDeviceLocked || keyguard.isKeyguardLocked || !context.getSystemService(PowerManager::class.java).isInteractive
+    }
+
+    @SuppressLint("MissingPermission") // Checked on every publication, including action completion.
+    private fun publish() {
+        val id = item ?: return
+        val nonce = ticket ?: return
+        if (!authorized()) { clear(); return }
+        if (locked()) body = null
+        val text = body ?: "**"
+        val toggle = if (body == null) SHOW else HIDE
+        val public = Notification.Builder(context, INBOX_CHANNEL)
+            .setSmallIcon(R.drawable.ic_launcher).setContentTitle("加密收件箱").setContentText("**").build()
+        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS)
+        val notification = Notification.Builder(context, INBOX_CHANNEL)
+            .setSmallIcon(R.drawable.ic_launcher).setContentTitle("加密收件箱")
+            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
+            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
+            .setLocalOnly(true).setOnlyAlertOnce(true).setContentIntent(open)
+            .setDeleteIntent(track(serviceIntent(context, DISMISS, id, nonce)))
+            .addAction(Notification.Action.Builder(null, if (body == null) "显示" else "隐藏",
+                track(serviceIntent(context, toggle, id, nonce))).setAuthenticationRequired(true).build())
+            .addAction(Notification.Action.Builder(null, "复制", track(serviceIntent(context, COPY, id, nonce)))
+                .setAuthenticationRequired(true).build())
+            .build()
+        try { manager.notify(INBOX_ID, notification) }
+        catch (_: SecurityException) { clear() }
+    }
+
+    private fun track(intent: PendingIntent): PendingIntent = intent.also { pending.add(it) }
+
+    companion object {
+        const val SERVICE_ID = 100
+        const val INBOX_ID = 101
+        const val SERVICE_CHANNEL = "background-sync"
+        const val INBOX_CHANNEL = "encrypted-inbox"
+        const val START = "io.github.infinifar.rustclipsync.LISTEN"
+        const val STOP = "io.github.infinifar.rustclipsync.STOP"
+        const val SHOW = "io.github.infinifar.rustclipsync.SHOW"
+        const val HIDE = "io.github.infinifar.rustclipsync.HIDE"
+        const val DISMISS = "io.github.infinifar.rustclipsync.DISMISS"
+        const val COPY = "io.github.infinifar.rustclipsync.COPY"
+        private const val ITEM = "item"
+        private const val TICKET = "ticket"
+        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
+
+        fun channels(context: Context) {
+            context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
+                NotificationChannel(SERVICE_CHANNEL, "后台监听", NotificationManager.IMPORTANCE_LOW),
+                NotificationChannel(INBOX_CHANNEL, "加密收件箱", NotificationManager.IMPORTANCE_LOW)
+                    .apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE },
+            ))
+        }
+
+        fun authorized(context: Context): Boolean {
+            val manager = context.getSystemService(NotificationManager::class.java)
+            return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
+                && manager.areNotificationsEnabled()
+                && manager.getNotificationChannel(INBOX_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
+        }
+
+        fun serviceNotification(context: Context, write: Boolean): Notification {
+            channels(context)
+            return Notification.Builder(context, SERVICE_CHANNEL)
+                .setSmallIcon(R.drawable.ic_launcher).setContentTitle("RustClipSync 后台监听")
+                .setContentText(if (write) "后台接收与写入已启用；敏感内容遵循所选策略" else "后台接收加密收件箱；不读写系统剪贴板")
+                .setOngoing(true).setOnlyAlertOnce(true).setLocalOnly(true)
+                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
+                .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS))
+                .addAction(Notification.Action.Builder(null, "停止监听", serviceIntent(context, STOP)).build())
+                .build()
+        }
+
+        private fun itemIntent(context: Context, target: Class<*>, action: String, id: String, ticket: String) =
+            Intent(context, target).setAction(action)
+                .setData(Uri.Builder().scheme("rustclipsync").authority("inbox").appendPath(ticket).build())
+                .putExtra(ITEM, id).putExtra(TICKET, ticket)
+
+        private fun serviceIntent(context: Context, action: String, id: String = "", ticket: String = "") =
+            PendingIntent.getService(context, 0, itemIntent(context, BackgroundSyncService::class.java, action, id, ticket), FLAGS)
+    }
+}
diff --git a/android/app/src/main/java/io/github/infinifar/rustclipsync/MainActivity.kt b/android/app/src/main/java/io/github/infinifar/rustclipsync/MainActivity.kt
index e3f507f..210b7c9 100644
--- a/android/app/src/main/java/io/github/infinifar/rustclipsync/MainActivity.kt
+++ b/android/app/src/main/java/io/github/infinifar/rustclipsync/MainActivity.kt
@@ -1,15 +1,25 @@
 package io.github.infinifar.rustclipsync
 
+import android.Manifest
+import android.app.Activity
+import android.content.Context
+import android.content.Intent
+import android.content.pm.PackageManager
+import android.os.Build
 import android.os.Bundle
+import android.provider.Settings
 import android.view.WindowManager
 import androidx.activity.ComponentActivity
 import androidx.activity.compose.setContent
+import androidx.activity.compose.rememberLauncherForActivityResult
+import androidx.activity.result.contract.ActivityResultContracts
 import androidx.compose.foundation.layout.*
 import androidx.compose.foundation.rememberScrollState
 import androidx.compose.foundation.verticalScroll
 import androidx.compose.material3.*
 import androidx.compose.runtime.*
 import androidx.compose.ui.Modifier
+import androidx.compose.ui.platform.LocalContext
 import androidx.compose.ui.text.input.PasswordVisualTransformation
 import androidx.compose.ui.unit.dp
 import io.github.infinifar.rustclipsync.bindings.SensitivePolicy
@@ -22,8 +32,12 @@ class MainActivity : ComponentActivity() {
         session = (application as SyncApplication).session
         setContent { MaterialTheme { SyncScreen(session) } }
     }
-    override fun onPause() { session.close(); super.onPause() }
-    override fun onDestroy() { session.close(); super.onDestroy() }
+    override fun onResume() { super.onResume(); session.uiVisible(true) }
+    override fun onPause() { session.uiVisible(false); super.onPause() }
+    override fun onWindowFocusChanged(hasFocus: Boolean) {
+        super.onWindowFocusChanged(hasFocus)
+        if (::session.isInitialized) session.clipboardFocus(hasFocus)
+    }
 }
 
 @Composable
@@ -31,13 +45,33 @@ private fun SyncScreen(session: SessionController) {
     var server by remember { mutableStateOf("") }
     var username by remember { mutableStateOf("") }
     var password by remember { mutableStateOf("") }
-    var policy by remember { mutableStateOf(SensitivePolicy.DISABLED) }
+    var policy by remember { mutableStateOf(session.policySelection) }
     var confirmAutoWrite by remember { mutableStateOf(false) }
+    var confirmBackgroundWrite by remember { mutableStateOf(false) }
+    var notificationRationale by remember { mutableStateOf(false) }
     val editable = !session.connected && !session.busy
+    val context = LocalContext.current
+    val notificationPreferences = remember { context.getSharedPreferences("notification-permission", Context.MODE_PRIVATE) }
+    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
+        notificationPreferences.edit().putBoolean("requested", true).apply()
+        session.refreshNotificationAuthorization()
+    }
+    fun notificationSettings() {
+        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
+    }
+    fun requestNotificationPermission() {
+        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
+            notificationSettings()
+        } else if ((context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true) {
+            notificationRationale = true
+        } else if (notificationPreferences.getBoolean("requested", false)) {
+            notificationSettings()
+        } else permission.launch(Manifest.permission.POST_NOTIFICATIONS)
+    }
     Surface(Modifier.fillMaxSize()) {
         Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
             Text("RustClipSync", style = MaterialTheme.typography.headlineLarge)
-            Text("Android 独立客户端 · 前台手动同步")
+            Text("Android 独立客户端 · 手动同步与可选后台收件箱")
             OutlinedTextField(server, { server = it }, label = { Text("HTTPS 服务器地址") }, enabled = editable, singleLine = true)
             OutlinedTextField(username, { username = it }, label = { Text("账户") }, enabled = editable, singleLine = true)
             OutlinedTextField(password, { password = it }, label = { Text("密码（仅本次内存会话）") }, enabled = editable, singleLine = true, visualTransformation = PasswordVisualTransformation())
@@ -52,6 +86,26 @@ private fun SyncScreen(session: SessionController) {
                 }
             }
             Text("未标记的文本无法保证识别为敏感内容。系统敏感提示不能阻止其他应用读取当前剪贴板。")
+            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
+                Switch(checked = session.backgroundEnabled, enabled = !session.busy, onCheckedChange = { enabled ->
+                    session.configureBackgroundListening(enabled)
+                    if (enabled && !session.notificationAuthorized) requestNotificationPermission()
+                })
+                Text("后台监听（可选）", Modifier.padding(top = 12.dp))
+            }
+            Text("后台不读取系统剪贴板。关闭后台写入时，仅接收加密收件箱，普通消息留待前台。约每 30 秒检查，系统可能延迟或停止。")
+            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
+                Switch(checked = session.backgroundWritesEnabled, enabled = session.backgroundEnabled && !session.busy, onCheckedChange = { value ->
+                    if (value) confirmBackgroundWrite = true else session.configureBackgroundWrites(false)
+                })
+                Text("允许后台自动写入剪贴板", Modifier.padding(top = 12.dp))
+            }
+            if (session.backgroundEnabled) {
+                Text(if (session.backgroundRunning) "后台监听服务运行中" else "连接后从前台启动监听服务")
+                Text(if (session.notificationAuthorized) "收件箱通知默认 **；可显示／隐藏、复制" else "通知未授权或频道关闭：继续保存收件箱，不展示消息通知")
+                if (!session.notificationAuthorized) TextButton(onClick = ::requestNotificationPermission) { Text("允许收件箱通知") }
+                TextButton(onClick = ::notificationSettings) { Text("通知权限与频道设置") }
+            }
             if (session.connected) {
                 Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                     Button(onClick = session::send, enabled = !session.busy) { Text("发送当前剪贴板") }
@@ -74,7 +128,7 @@ private fun SyncScreen(session: SessionController) {
                     OutlinedButton(onClick = session::clearInbox, enabled = !session.busy) { Text("删除") }
                 }
             }
-            Text("离开前台即关闭；不在后台监视剪贴板。首次注册以服务器当前版本为基线，不回放旧消息。")
+            Text("后台监听默认关闭；未启用时离开前台即关闭会话。服务受系统时限约束，不在开机或进程重启后自动连接。首次注册不回放旧消息。")
         }
     }
     if (confirmAutoWrite) AlertDialog(onDismissRequest = { confirmAutoWrite = false },
@@ -82,4 +136,14 @@ private fun SyncScreen(session: SessionController) {
         text = { Text("接收的已标记敏感内容会替换系统剪贴板，其他应用可能读取。") },
         confirmButton = { TextButton(onClick = { confirmAutoWrite = false; policy = SensitivePolicy.AUTO_WRITE; if (session.connected) session.policy(policy) }) { Text("启用") } },
         dismissButton = { TextButton(onClick = { confirmAutoWrite = false }) { Text("取消") } })
+    if (confirmBackgroundWrite) AlertDialog(onDismissRequest = { confirmBackgroundWrite = false },
+        title = { Text("允许后台覆盖系统剪贴板？") },
+        text = { Text("后台接收会替换剪贴板，可能覆盖你在其他应用刚复制的内容。后台无法读取并比较当前值；敏感内容仍遵循跳过、加密收件箱或显式自动写入策略。") },
+        confirmButton = { TextButton(onClick = { confirmBackgroundWrite = false; session.configureBackgroundWrites(true) }) { Text("允许写入") } },
+        dismissButton = { TextButton(onClick = { confirmBackgroundWrite = false }) { Text("取消") } })
+    if (notificationRationale) AlertDialog(onDismissRequest = { notificationRationale = false },
+        title = { Text("允许加密收件箱通知？") },
+        text = { Text("授权后，后台收到收件箱消息时显示隐藏预览和操作按钮。拒绝授权仍可保存加密收件箱。") },
+        confirmButton = { TextButton(onClick = { notificationRationale = false; if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("继续授权") } },
+        dismissButton = { TextButton(onClick = { notificationRationale = false }) { Text("暂不") } })
 }
diff --git a/android/app/src/main/java/io/github/infinifar/rustclipsync/SessionController.kt b/android/app/src/main/java/io/github/infinifar/rustclipsync/SessionController.kt
index b10d22c..aeb5faf 100644
--- a/android/app/src/main/java/io/github/infinifar/rustclipsync/SessionController.kt
+++ b/android/app/src/main/java/io/github/infinifar/rustclipsync/SessionController.kt
@@ -1,12 +1,14 @@
 package io.github.infinifar.rustclipsync
 
 import android.content.Context
+import android.content.Intent
 import androidx.compose.runtime.getValue
 import androidx.compose.runtime.mutableStateOf
 import androidx.compose.runtime.setValue
 import io.github.infinifar.rustclipsync.bindings.MobileConfig
 import io.github.infinifar.rustclipsync.bindings.MobileException
 import io.github.infinifar.rustclipsync.bindings.MobileSession
+import io.github.infinifar.rustclipsync.bindings.Platform
 import io.github.infinifar.rustclipsync.bindings.SensitivePolicy
 import io.github.infinifar.rustclipsync.bindings.SessionStatus
 import kotlinx.coroutines.CancellationException
@@ -14,78 +16,219 @@ import kotlinx.coroutines.CoroutineScope
 import kotlinx.coroutines.Dispatchers
 import kotlinx.coroutines.Job
 import kotlinx.coroutines.SupervisorJob
+import kotlinx.coroutines.delay
 import kotlinx.coroutines.launch
 import kotlinx.coroutines.withContext
 
-/** UI owns this wrapper; generated objects and cleanup stay here. All entry points are main-thread. */
-internal class SessionController(context: Context) {
+/** One process owner for UI and service; all entry points and observable state stay on main. */
+internal class SessionController(
+    context: Context,
+    private val resources: (Context) -> Pair<SessionPlatform, ByteArray> = {
+        val store = SecureState(it); AndroidPlatform(it, store) to store.key()
+    },
+    private val open: (MobileConfig, Platform) -> MobileSession = { config, adapter -> MobileSession(config, adapter) },
+) {
     private val application = context.applicationContext
+    private val preferences = application.getSharedPreferences("background-listening", Context.MODE_PRIVATE)
     private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
     private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Default)
+    internal val notifications = InboxNotifications(application)
     private var native: MobileSession? = null
-    private var platform: AndroidPlatform? = null
+    private var platform: SessionPlatform? = null
     private var retiring: Job? = null
     private var epoch = 0L
+    private var startingService = false
+    private var focused = false
+    private var selectedPolicy by mutableStateOf(SensitivePolicy.DISABLED)
+    val policySelection get() = selectedPolicy
+    var notificationAuthorized by mutableStateOf(InboxNotifications.authorized(application)); private set
+    var foreground by mutableStateOf(false); private set
+    var backgroundEnabled by mutableStateOf(preferences.getBoolean("enabled", false)); private set
+    var backgroundWritesEnabled by mutableStateOf(preferences.getBoolean("write", false)); private set
+    var backgroundRunning by mutableStateOf(false); private set
     var busy by mutableStateOf(false); private set
     var connected by mutableStateOf(false); private set
     var status by mutableStateOf<SessionStatus?>(null); private set
     var message by mutableStateOf("仅在前台手动同步"); private set
 
     fun connect(server: String, username: String, password: String, policy: SensitivePolicy) {
-        if (busy || native != null) return
+        if (busy || native != null || !foreground || !focused) return
         val version = ++epoch
+        selectedPolicy = policy
         busy = true
         scope.launch {
             try {
-                // A previous executor must release its store before the same state can reopen.
                 retiring?.join()
-                val (store, key) = withContext(Dispatchers.IO) { val store = SecureState(application); store to store.key() }
-                if (version != epoch) { key.fill(0); return@launch }
-                val adapter = AndroidPlatform(application, store)
-                val session = try { MobileSession(MobileConfig(server, username, password, key, policy), adapter) }
+                val (adapter, key) = withContext(Dispatchers.IO) { resources(application) }
+                if (version != epoch) { key.fill(0); adapter.revoke(); return@launch }
+                val session = try { open(MobileConfig(server, username, password, key, policy), adapter) }
                     finally { key.fill(0) }
                 platform = adapter; native = session
-                adapter.observe(session)
+                adapter.focus(focused); adapter.observe(session)
                 session.check()
                 val value = session.status()
-                if (version == epoch) { status = value; connected = true; message = "已连接" }
+                if (version == epoch) {
+                    status = value; connected = true; message = "已连接"
+                    startBackgroundIfEnabled()
+                }
             } catch (error: Exception) {
                 if (error is CancellationException) throw error
                 if (version == epoch) { close(); message = safeError(error) }
             } finally { if (version == epoch) busy = false }
         }
     }
-    fun send() = command { sendClipboard().toString() }
-    fun receive() = command { receive().toString() }
-    fun retry() = command { retryPending().toString() }
-    fun policy(value: SensitivePolicy) = command { updatePolicy(value); "敏感策略已更新" }
-    fun copyInbox() { val id = status?.inbox?.id ?: return; command { copyInbox(id); "已显式复制敏感内容" } }
-    fun clearInbox() { val id = status?.inbox?.id ?: return; command { clearInbox(id); "收件箱已清空" } }
-    private fun command(operation: suspend MobileSession.() -> String) {
+
+    fun uiVisible(value: Boolean) {
+        foreground = value
+        updateWriteAccess()
+        native?.clipboardChanged() // Invalidate an in-flight receive across a lifecycle transition.
+        if (value) { notifications.clear(); refreshNotificationAuthorization(); startBackgroundIfEnabled() }
+        else {
+            clipboardFocus(false)
+            if (!backgroundEnabled || (!backgroundRunning && !startingService)) close()
+        }
+    }
+
+    fun clipboardFocus(value: Boolean) {
+        focused = value && foreground
+        platform?.focus(focused)
+        native?.clipboardChanged()
+    }
+
+    fun configureBackgroundListening(value: Boolean) {
+        backgroundEnabled = value
+        native?.clipboardChanged()
+        updateWriteAccess()
+        preferences.edit().putBoolean("enabled", value).apply()
+        if (value) startBackgroundIfEnabled() else {
+            startingService = false; backgroundRunning = false
+            notifications.clear()
+            application.stopService(Intent(application, BackgroundSyncService::class.java))
+            if (!foreground) close()
+        }
+    }
+
+    fun configureBackgroundWrites(value: Boolean) {
+        backgroundWritesEnabled = value
+        preferences.edit().putBoolean("write", value).apply()
+        native?.clipboardChanged()
+        updateWriteAccess()
+        if (backgroundRunning && foreground) application.startService(Intent(application, BackgroundSyncService::class.java).setAction(InboxNotifications.START))
+    }
+
+    private fun updateWriteAccess() {
+        platform?.backgroundWrite(backgroundEnabled && backgroundRunning && backgroundWritesEnabled && !foreground)
+    }
+
+    private fun startBackgroundIfEnabled() {
+        if (!backgroundEnabled || !connected || !foreground || backgroundRunning || startingService) return
+        startingService = true
+        try { application.startForegroundService(Intent(application, BackgroundSyncService::class.java).setAction(InboxNotifications.START)) }
+        catch (_: RuntimeException) {
+            startingService = false
+            message = "系统未允许启动后台监听；请在前台重试"
+            if (!foreground) close()
+        }
+    }
+
+    fun serviceStarted(): Boolean {
+        startingService = false
+        backgroundRunning = backgroundEnabled && connected
+        updateWriteAccess()
+        return backgroundRunning
+    }
+
+    fun serviceStopped() {
+        startingService = false; backgroundRunning = false
+        updateWriteAccess()
+        notifications.clear()
+        if (!foreground) close()
+    }
+
+    fun serviceTimedOut() {
+        configureBackgroundListening(false)
+        message = "后台监听已达到系统时限并停止；可回到前台重新启用"
+    }
+
+    fun notificationEligible(): Boolean = backgroundEnabled && backgroundRunning && connected && !foreground && selectedPolicy == SensitivePolicy.ENCRYPTED_INBOX
+
+    fun refreshNotificationAuthorization() {
+        notificationAuthorized = InboxNotifications.authorized(application)
+        notifications.refreshAuthorization()
+    }
+
+    /** A scheduling tick, not an upload retry loop. Core owns all receive/ack rules. */
+    suspend fun pollInbox() {
+        refreshNotificationAuthorization()
+        if (!backgroundEnabled || !backgroundRunning || !connected || foreground || busy) return
+        if (!backgroundWritesEnabled && selectedPolicy != SensitivePolicy.ENCRYPTED_INBOX) return
+        val previous = status?.inbox?.id
+        perform { receiveBackground(backgroundWritesEnabled); "后台接收已检查" }
+        val id = status?.inbox?.id
+        if (notificationEligible() && id != null && id != previous) notifications.offer(id)
+    }
+
+    suspend fun notificationAction(intent: Intent) = notifications.action(intent, ::notificationEligible) { id ->
+        val session = native ?: return@action null
+        val version = epoch
+        val text = session.readInbox(id)
+        if (version == epoch && status?.inbox?.id == id) text else null
+    }
+
+    suspend fun notificationCopy(intent: Intent) {
+        if (!notificationEligible()) return
+        val permit = notifications.claimCopy(intent) ?: return
+        val id = permit.id
+        val version = epoch
+        repeat(80) { if (busy && version == epoch) delay(25) }
+        if (busy || version != epoch || !notificationEligible() || notifications.claimCopy(intent) != permit || status?.inbox?.id != id) return
+        val adapter = platform ?: return
+        // One explicit operation, bound to this item. Recheck authorization,
+        // lifecycle, current ticket and lock state inside the main-thread write.
+        adapter.explicitCopy(id) { version == epoch && notificationEligible() && notifications.claimCopy(intent) == permit }
+        try { perform { copyInbox(id); notifications.mask(); "已显式复制敏感内容" } }
+        finally { adapter.explicitCopy(null) { false } }
+    }
+
+    fun send() { if (focused) command { sendClipboard().toString() } }
+    fun receive() { if (focused) command { receive().toString() } }
+    fun retry() { if (focused) command { retryPending().toString() } }
+    fun policy(value: SensitivePolicy) = command {
+        updatePolicy(value); selectedPolicy = value; notifications.clear(); "敏感策略已更新"
+    }
+    fun copyInbox() {
+        val id = status?.inbox?.id ?: return
+        if (focused) command { copyInbox(id); "已显式复制敏感内容" }
+    }
+    fun clearInbox() { val id = status?.inbox?.id ?: return; command { clearInbox(id); notifications.clear(); "收件箱已清空" } }
+    private fun command(operation: suspend MobileSession.() -> String) { scope.launch { perform(operation) } }
+    private suspend fun perform(operation: suspend MobileSession.() -> String) {
         val session = native ?: return
         if (busy || !connected) return
         val version = epoch
         busy = true
-        scope.launch {
-            try {
-                val result = session.operation(); val value = session.status()
-                if (version == epoch) { message = result; status = value }
-            } catch (error: Exception) {
-                if (error is CancellationException) throw error
-                if (version == epoch) {
-                    if (error is MobileException.Storage || error is MobileException.Closed || error is MobileException.Internal) close()
-                    else {
-                        val value = try { session.status() } catch (_: Exception) { null }
-                        if (version != epoch) return@launch
-                        status = value
-                    }
-                    message = safeError(error)
+        try {
+            val result = session.operation(); val value = session.status()
+            if (version == epoch) { message = result; status = value }
+        } catch (error: Exception) {
+            if (error is CancellationException) throw error
+            if (version == epoch) {
+                if (error is MobileException.Storage || error is MobileException.Closed || error is MobileException.Internal) close()
+                else {
+                    val value = try { session.status() } catch (_: Exception) { null }
+                    if (version != epoch) return
+                    status = value
                 }
-            } finally { if (version == epoch) busy = false }
-        }
+                message = safeError(error)
+            }
+        } finally { if (version == epoch) busy = false }
     }
+
     fun close() {
         ++epoch
+        startingService = false; backgroundRunning = false
+        notifications.clear()
+        application.stopService(Intent(application, BackgroundSyncService::class.java))
         platform?.revoke(); platform = null
         val session = native; native = null
         connected = false; busy = false; status = null; message = "已关闭；回到前台后可重新连接"
@@ -95,7 +238,7 @@ internal class SessionController(context: Context) {
             retiring = cleanup.launch {
                 previous?.join()
                 try { session.shutdown() }
-                catch (_: Exception) { /* Already revoked; a failed executor must still release its binding. */ }
+                catch (_: Exception) { /* Revoked; still release the binding. */ }
                 finally { session.destroy() }
             }
         }
diff --git a/android/app/src/main/java/io/github/infinifar/rustclipsync/SyncApplication.kt b/android/app/src/main/java/io/github/infinifar/rustclipsync/SyncApplication.kt
index eedd88f..28ed18d 100644
--- a/android/app/src/main/java/io/github/infinifar/rustclipsync/SyncApplication.kt
+++ b/android/app/src/main/java/io/github/infinifar/rustclipsync/SyncApplication.kt
@@ -4,5 +4,7 @@ import android.app.Application
 
 /** One storage owner per process, including Activity recreation while an old close is in flight. */
 class SyncApplication : Application() {
-    internal val session by lazy { SessionController(this) }
+    // Internal dependency injection also lets instrumentation supply isolated adapters.
+    internal lateinit var session: SessionController
+    override fun onCreate() { super.onCreate(); session = SessionController(this) }
 }
```
