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
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
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
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        session = (application as SyncApplication).session
        setContent { AppTheme { mode, changeTheme -> SyncScreen(session, mode, changeTheme) } }
    }
    override fun onResume() { super.onResume(); session.uiVisible(true) }
    override fun onPause() { session.uiVisible(false); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::session.isInitialized) session.clipboardFocus(hasFocus)
    }
}

@Composable
private fun SyncScreen(session: SessionController, theme: ThemeMode, changeTheme: (ThemeMode) -> Unit) {
    var server by rememberSaveable { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var policy by remember { mutableStateOf(session.policySelection) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
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
    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("同步" to android.R.drawable.ic_menu_send, "收件箱" to R.drawable.ic_stat_clipboard, "设置" to android.R.drawable.ic_menu_preferences).forEachIndexed { index, (title, icon) ->
                    NavigationBarItem(selected = page == index, onClick = { page = index },
                        icon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp)) },
                        label = { Text(title) })
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
            key(page) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(listOf("同步", "加密收件箱", "设置")[page], style = MaterialTheme.typography.headlineLarge)
                    Text("RustClipSync", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (page) {
                    0 -> {
                        Panel(if (session.connected) "会话已连接" else "连接服务器") {
                            if (!session.connected) {
                                OutlinedTextField(server, { server = it }, label = { Text("HTTPS 服务器地址") },
                                    modifier = Modifier.fillMaxWidth(), enabled = editable, singleLine = true)
                                OutlinedTextField(username, { username = it }, label = { Text("账户") },
                                    modifier = Modifier.fillMaxWidth(), enabled = editable, singleLine = true)
                                OutlinedTextField(password, { password = it }, label = { Text("密码") },
                                    modifier = Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                                    visualTransformation = PasswordVisualTransformation())
                                Note("密码仅用于本次会话；服务器填写部署根地址。敏感策略可在设置中调整。")
                                Button(onClick = { session.connect(server.trim(), username, password, policy); password = "" },
                                    enabled = editable && server.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("连接并注册设备") }
                            } else {
                                Text(server.ifBlank { "当前服务器" }, style = MaterialTheme.typography.bodyLarge)
                                Note("当前敏感策略：" + policyTitle(session.policySelection))
                                Button(onClick = session::send, enabled = !session.busy, modifier = Modifier.fillMaxWidth()) { Text("发送当前剪贴板") }
                                OutlinedButton(onClick = session::receive, enabled = !session.busy, modifier = Modifier.fillMaxWidth()) { Text("接收最新内容") }
                                if (session.status?.hasPending == true) {
                                    OutlinedButton(onClick = session::retry, enabled = !session.busy, modifier = Modifier.fillMaxWidth()) { Text("重试待处理事务") }
                                }
                                TextButton(onClick = session::close) { Text("关闭会话") }
                            }
                        }
                        Panel("同步状态") {
                            if (session.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(session.message)
                            session.status?.let { Note("游标 ${it.cursor} · ${if (it.hasPending) "有待处理事务" else "无待处理事务"}") }
                            Note(if (session.backgroundRunning) "后台监听运行中" else "后台监听未运行")
                        }
                        Note("首次注册不回放旧消息。未启用后台监听时，离开前台会关闭会话。")
                    }
                    1 -> {
                        Panel(if (session.status?.inbox != null) "有一条敏感消息" else "收件箱为空") {
                            if (session.status?.inbox != null) {
                                Text("正文默认隐藏，保存在本机加密收件箱。")
                                Button(onClick = session::copyInbox, enabled = !session.busy, modifier = Modifier.fillMaxWidth()) { Text("复制敏感内容") }
                                TextButton(onClick = { confirmDelete = true }, enabled = !session.busy) { Text("删除消息") }
                            } else {
                                Text(if (session.connected) "仅已明确标记的敏感消息会进入收件箱，请先选择加密收件箱策略。" else "连接服务器后，可接收已标记的敏感消息。")
                                TextButton(onClick = { page = if (session.connected) 2 else 0 }) { Text(if (session.connected) "前往设置" else "前往连接") }
                            }
                        }
                        Panel("通知栏快捷操作") {
                            Text(session.inboxNotificationHint)
                            Note("展开通知后，显示／隐藏保留在通知栏；复制会收起通知栏。显示与复制前请先解锁设备。")
                            OutlinedButton(onClick = session::showInboxNotification,
                                enabled = session.inboxNotificationReady && !session.busy, modifier = Modifier.fillMaxWidth()) { Text("显示收件箱通知") }
                            TextButton(onClick = ::notificationSettings) { Text("通知权限与频道") }
                        }
                        Note("收件箱仅保留最新一项。普通未标记文本不会自动识别为敏感内容。")
                    }
                    2 -> {
                        Panel("外观") {
                            Column(Modifier.selectableGroup()) {
                                ThemeMode.entries.forEach { value ->
                                    Choice(value.title, theme == value, true) { changeTheme(value) }
                                }
                            }
                        }
                        Panel("敏感内容") {
                            Column(Modifier.selectableGroup()) {
                                SensitivePolicy.entries.forEach { value ->
                                    Choice(policyTitle(value), policy == value, !session.busy) {
                                        if (value == SensitivePolicy.AUTO_WRITE) confirmAutoWrite = true
                                        else { policy = value; if (session.connected) session.policy(value) }
                                    }
                                }
                            }
                            Note("未标记文本无法保证识别为敏感内容。自动写入会替换系统剪贴板。")
                        }
                        Panel("后台接收") {
                            SettingSwitch("后台监听", "约每 30 秒检查；不读取系统剪贴板。", session.backgroundEnabled, !session.busy) { enabled ->
                                session.configureBackgroundListening(enabled)
                                if (enabled && !session.notificationAuthorized) requestNotificationPermission()
                            }
                            HorizontalDivider()
                            SettingSwitch("自动写入剪贴板", "可能覆盖其他应用刚复制的内容。敏感消息仍遵循上面的策略。",
                                session.backgroundWritesEnabled, session.backgroundEnabled && !session.busy) { enabled ->
                                if (enabled) confirmBackgroundWrite = true else session.configureBackgroundWrites(false)
                            }
                            if (session.backgroundEnabled) {
                                Note(if (session.backgroundRunning) "监听服务运行中" else "连接后启动监听服务")
                                if (!session.notificationAuthorized) TextButton(onClick = ::requestNotificationPermission) { Text("允许收件箱通知") }
                            }
                            Note("系统可能延迟或停止服务；重启后不自动连接，不保存账户密码。")
                        }
                        Panel("关于") {
                            Text("RustClipSync ${BuildConfig.VERSION_NAME}")
                            Note("构建 ${BuildConfig.BUILD_REVISION}")
                            TextButton(onClick = ::notificationSettings) { Text("系统通知设置") }
                        }
                    }
                }
            }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("删除收件箱消息？") }, text = { Text("这会删除本机保存的这一项敏感内容，无法撤销。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; session.clearInbox() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
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

private fun policyTitle(value: SensitivePolicy): String = when (value) {
    SensitivePolicy.DISABLED -> "跳过敏感消息"
    SensitivePolicy.ENCRYPTED_INBOX -> "保存到加密收件箱"
    SensitivePolicy.AUTO_WRITE -> "自动写入剪贴板"
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Choice(title: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, enabled = enabled, onClick = null)
        Text(title, Modifier.weight(1f).padding(start = 12.dp))
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title); Note(detail) }
        Switch(checked = checked, enabled = enabled, onCheckedChange = change)
    }
}
