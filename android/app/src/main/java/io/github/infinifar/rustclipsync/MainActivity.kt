package io.github.infinifar.rustclipsync

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.infinifar.rustclipsync.bindings.SensitivePolicy

class MainActivity : ComponentActivity() {
    private lateinit var session: SessionController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        session = (application as SyncApplication).session
        setContent { MaterialTheme { SyncScreen(session) } }
    }
    override fun onResume() { super.onResume(); session.uiVisible(true) }
    override fun onPause() { session.uiVisible(false); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::session.isInitialized) session.clipboardFocus(hasFocus)
    }
}

@Composable
private fun SyncScreen(session: SessionController) {
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var policy by remember { mutableStateOf(session.policySelection) }
    var confirmAutoWrite by remember { mutableStateOf(false) }
    var confirmBackgroundWrite by remember { mutableStateOf(false) }
    var notificationRationale by remember { mutableStateOf(false) }
    val editable = !session.connected && !session.busy
    val context = LocalContext.current
    val notificationPreferences = remember { context.getSharedPreferences("notification-permission", Context.MODE_PRIVATE) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationPreferences.edit().putBoolean("requested", true).apply()
        session.refreshNotificationAuthorization()
    }
    fun notificationSettings() {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notificationSettings()
        } else if ((context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true) {
            notificationRationale = true
        } else if (notificationPreferences.getBoolean("requested", false)) {
            notificationSettings()
        } else permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("RustClipSync", style = MaterialTheme.typography.headlineLarge)
            Text("Android 独立客户端 · 手动同步与可选后台收件箱")
            Text("开发构建 ${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_REVISION}", style = MaterialTheme.typography.labelSmall)
            OutlinedTextField(server, { server = it }, label = { Text("HTTPS 服务器地址") }, enabled = editable, singleLine = true)
            OutlinedTextField(username, { username = it }, label = { Text("账户") }, enabled = editable, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text("密码（仅本次内存会话）") }, enabled = editable, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Text("已标记的敏感内容", style = MaterialTheme.typography.titleMedium)
            listOf(SensitivePolicy.DISABLED to "跳过", SensitivePolicy.ENCRYPTED_INBOX to "加密收件箱", SensitivePolicy.AUTO_WRITE to "自动写入剪贴板（显式启用）").forEach { (value, title) ->
                Row {
                    RadioButton(selected = policy == value, enabled = !session.busy, onClick = {
                        if (value == SensitivePolicy.AUTO_WRITE) confirmAutoWrite = true
                        else { policy = value; if (session.connected) session.policy(value) }
                    })
                    Text(title, Modifier.padding(top = 12.dp))
                }
            }
            Text("未标记的文本无法保证识别为敏感内容。系统敏感提示不能阻止其他应用读取当前剪贴板。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = session.backgroundEnabled, enabled = !session.busy, onCheckedChange = { enabled ->
                    session.configureBackgroundListening(enabled)
                    if (enabled && !session.notificationAuthorized) requestNotificationPermission()
                })
                Text("后台监听（可选）", Modifier.padding(top = 12.dp))
            }
            Text("后台不读取系统剪贴板。关闭后台写入时，仅接收加密收件箱，普通消息留待前台。约每 30 秒检查，系统可能延迟或停止。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = session.backgroundWritesEnabled, enabled = session.backgroundEnabled && !session.busy, onCheckedChange = { value ->
                    if (value) confirmBackgroundWrite = true else session.configureBackgroundWrites(false)
                })
                Text("允许后台自动写入剪贴板", Modifier.padding(top = 12.dp))
            }
            if (session.backgroundEnabled) {
                Text(if (session.backgroundRunning) "后台监听服务运行中" else "连接后从前台启动监听服务")
                Text(if (session.notificationAuthorized) "收件箱通知默认 **；可显示／隐藏、复制" else "通知未授权或频道关闭：继续保存收件箱，不展示消息通知")
                if (!session.notificationAuthorized) TextButton(onClick = ::requestNotificationPermission) { Text("允许收件箱通知") }
                TextButton(onClick = ::notificationSettings) { Text("通知权限与频道设置") }
                Text(session.inboxNotificationHint)
                OutlinedButton(onClick = session::showInboxNotification, enabled = session.inboxNotificationReady && !session.busy) { Text("显示收件箱通知") }
            }
            if (session.connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = session::send, enabled = !session.busy) { Text("发送当前剪贴板") }
                    Button(onClick = session::receive, enabled = !session.busy) { Text("接收") }
                }
                if (session.status?.hasPending == true) {
                    OutlinedButton(onClick = session::retry, enabled = !session.busy) { Text("重试待处理事务") }
                }
                OutlinedButton(onClick = session::close) { Text("关闭会话") }
            } else {
                Button(onClick = { session.connect(server.trim(), username, password, policy); password = "" }, enabled = !session.busy && server.isNotBlank()) { Text("连接并注册设备") }
            }
            if (session.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(session.message)
            session.status?.let { Text("游标 ${it.cursor} · ${if (it.hasPending) "有待处理事务" else "无待处理事务"}") }
            session.status?.inbox?.let {
                Text("加密收件箱有一条敏感消息；正文默认隐藏。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = session::copyInbox, enabled = !session.busy) { Text("显式复制") }
                    OutlinedButton(onClick = session::clearInbox, enabled = !session.busy) { Text("删除") }
                }
            }
            Text("后台监听默认关闭；未启用时离开前台即关闭会话。服务受系统时限约束，不在开机或进程重启后自动连接。首次注册不回放旧消息。")
        }
    }
    if (confirmAutoWrite) AlertDialog(onDismissRequest = { confirmAutoWrite = false },
        title = { Text("允许敏感内容自动写入？") },
        text = { Text("接收的已标记敏感内容会替换系统剪贴板，其他应用可能读取。") },
        confirmButton = { TextButton(onClick = { confirmAutoWrite = false; policy = SensitivePolicy.AUTO_WRITE; if (session.connected) session.policy(policy) }) { Text("启用") } },
        dismissButton = { TextButton(onClick = { confirmAutoWrite = false }) { Text("取消") } })
    if (confirmBackgroundWrite) AlertDialog(onDismissRequest = { confirmBackgroundWrite = false },
        title = { Text("允许后台覆盖系统剪贴板？") },
        text = { Text("后台接收会替换剪贴板，可能覆盖你在其他应用刚复制的内容。后台无法读取并比较当前值；敏感内容仍遵循跳过、加密收件箱或显式自动写入策略。") },
        confirmButton = { TextButton(onClick = { confirmBackgroundWrite = false; session.configureBackgroundWrites(true) }) { Text("允许写入") } },
        dismissButton = { TextButton(onClick = { confirmBackgroundWrite = false }) { Text("取消") } })
    if (notificationRationale) AlertDialog(onDismissRequest = { notificationRationale = false },
        title = { Text("允许加密收件箱通知？") },
        text = { Text("授权后，后台收到收件箱消息时显示隐藏预览和操作按钮。拒绝授权仍可保存加密收件箱。") },
        confirmButton = { TextButton(onClick = { notificationRationale = false; if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("继续授权") } },
        dismissButton = { TextButton(onClick = { notificationRationale = false }) { Text("暂不") } })
}
