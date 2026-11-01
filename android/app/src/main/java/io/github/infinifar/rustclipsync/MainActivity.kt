package io.github.infinifar.rustclipsync

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    override fun onPause() { session.close(); super.onPause() }
    override fun onDestroy() { session.close(); super.onDestroy() }
}

@Composable
private fun SyncScreen(session: SessionController) {
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var policy by remember { mutableStateOf(SensitivePolicy.DISABLED) }
    var confirmAutoWrite by remember { mutableStateOf(false) }
    val editable = !session.connected && !session.busy
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("RustClipSync", style = MaterialTheme.typography.headlineLarge)
            Text("Android 独立客户端 · 前台手动同步")
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
            if (session.connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = session::send, enabled = !session.busy) { Text("发送当前剪贴板") }
                    Button(onClick = session::receive, enabled = !session.busy) { Text("接收") }
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
            Text("离开前台即关闭；不在后台监视剪贴板。首次注册以服务器当前版本为基线，不回放旧消息。")
        }
    }
    if (confirmAutoWrite) AlertDialog(onDismissRequest = { confirmAutoWrite = false },
        title = { Text("允许敏感内容自动写入？") },
        text = { Text("接收的已标记敏感内容会替换系统剪贴板，其他应用可能读取。") },
        confirmButton = { TextButton(onClick = { confirmAutoWrite = false; policy = SensitivePolicy.AUTO_WRITE; if (session.connected) session.policy(policy) }) { Text("启用") } },
        dismissButton = { TextButton(onClick = { confirmAutoWrite = false }) { Text("取消") } })
}
