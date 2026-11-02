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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import android.net.Uri
import android.content.ActivityNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguages.context(newBase))
    }

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
    val policy = session.policySelection
    var settingsPage by rememberSaveable { mutableIntStateOf(0) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<UiMessage?>(null) }
    val uiScope = rememberCoroutineScope()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmAutoWrite by remember { mutableStateOf(false) }
    var confirmBackgroundRun by remember { mutableStateOf(false) }
    var notificationRationale by remember { mutableStateOf(false) }
    val editable = !session.connected && !session.busy
    val context = LocalContext.current
    val notificationPreferences = remember { context.getSharedPreferences("notification-permission", Context.MODE_PRIVATE) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationPreferences.edit().putBoolean("requested", true).apply()
        session.refreshNotificationAuthorization()
    }
    fun openProject(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { updateMessage = UiMessage(R.string.no_browser) }
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
                listOf(context.translated(R.string.nav_sync) to android.R.drawable.ic_menu_send, context.translated(R.string.nav_inbox) to R.drawable.ic_stat_clipboard, context.translated(R.string.nav_settings) to android.R.drawable.ic_menu_preferences).forEachIndexed { index, (title, icon) ->
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
                    Text(listOf(context.translated(R.string.nav_sync), context.translated(R.string.inbox_title), context.translated(R.string.nav_settings))[page], style = MaterialTheme.typography.headlineLarge)
                    Text("RustClipSync", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (page) {
                    0 -> {
                        Panel(if (session.connected) context.translated(R.string.session_connected) else context.translated(R.string.connect_server)) {
                            if (!session.connected) {
                                OutlinedTextField(server, { server = it }, label = { Text(context.translated(R.string.server_address)) },
                                    modifier = Modifier.fillMaxWidth(), enabled = editable, singleLine = true)
                                OutlinedTextField(username, { username = it }, label = { Text(context.translated(R.string.account)) },
                                    modifier = Modifier.fillMaxWidth(), enabled = editable, singleLine = true)
                                OutlinedTextField(password, { password = it }, label = { Text(context.translated(R.string.password)) },
                                    modifier = Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                                    visualTransformation = PasswordVisualTransformation())
                                Note(context.translated(R.string.connection_note))
                                Button(onClick = { session.connect(server.trim(), username, password, policy); password = "" },
                                    enabled = editable && server.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(context.translated(R.string.connect_register)) }
                            } else {
                                Text(server.ifBlank { context.translated(R.string.current_server) }, style = MaterialTheme.typography.bodyLarge)
                                Note(context.translated(R.string.sensitive_policy_prefix) + context.translated(policyTitle(session.policySelection)))
                                Button(onClick = session::send, enabled = !session.busy && session.direction.sends, modifier = Modifier.fillMaxWidth()) { Text(context.translated(R.string.send_clipboard)) }
                                OutlinedButton(onClick = session::receive, enabled = !session.busy && session.direction.receives, modifier = Modifier.fillMaxWidth()) { Text(context.translated(R.string.receive_latest)) }
                                if (session.status?.hasPending == true) {
                                    OutlinedButton(onClick = session::retry, enabled = !session.busy, modifier = Modifier.fillMaxWidth()) { Text(if (session.direction.sends) context.translated(R.string.retry_pending) else context.translated(R.string.cancel_old_upload)) }
                                }
                                TextButton(onClick = session::close) { Text(context.translated(R.string.close_session)) }
                            }
                        }
                        Panel(context.translated(R.string.sync_status)) {
                            if (session.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(session.message)
                            session.status?.let {
                                Note(context.translated(R.string.device_uuid))
                                SelectionContainer { Text(it.deviceId, style = MaterialTheme.typography.bodyMedium) }
                                Note(context.translated(R.string.sync_cursor, it.cursor.toString(), context.translated(if (it.hasPending) R.string.pending_yes else R.string.pending_no)))
                            }
                            Note(context.translated(R.string.direction_status, context.translated(session.direction.title)))
                            Note(if (session.backgroundRunning) context.translated(R.string.background_running) else context.translated(R.string.background_stopped))
                        }
                        Note(context.translated(R.string.first_pair_note))
                    }
                    1 -> {
                        Panel(if (session.status?.inbox != null) context.translated(R.string.inbox_has_message) else context.translated(R.string.inbox_empty)) {
                            if (session.status?.inbox != null) {
                                Text(context.translated(R.string.inbox_masked_note))
                                Button(onClick = session::copyInbox, enabled = !session.busy, modifier = Modifier.fillMaxWidth()) { Text(context.translated(R.string.copy_sensitive)) }
                                TextButton(onClick = { confirmDelete = true }, enabled = !session.busy) { Text(context.translated(R.string.delete_message)) }
                            } else {
                                Text(if (session.connected) context.translated(R.string.inbox_policy_note) else context.translated(R.string.inbox_connect_note))
                                TextButton(onClick = { settingsPage = 0; page = if (session.connected) 2 else 0 }) { Text(if (session.connected) context.translated(R.string.go_settings) else context.translated(R.string.go_connect)) }
                            }
                        }
                        Panel(context.translated(R.string.notification_actions)) {
                            Text(session.inboxNotificationHint)
                            Note(session.message)
                            if (session.filterDownloads && session.filterMode != io.github.infinifar.rustclipsync.bindings.TextFilterMode.OFF) {
                                Note(context.translated(R.string.inbox_receive_filter_active))
                            }
                            Note(context.translated(R.string.sensitive_policy_prefix) + context.translated(policyTitle(policy)))
                            TextButton(onClick = { settingsPage = 0; page = 2 }) { Text(context.translated(R.string.review_sync_settings)) }
                            Note(context.translated(R.string.notification_actions_note))
                            OutlinedButton(onClick = session::showInboxNotification,
                                enabled = session.inboxNotificationReady && !session.busy, modifier = Modifier.fillMaxWidth()) { Text(context.translated(R.string.show_inbox_notification)) }
                            TextButton(onClick = ::notificationSettings) { Text(context.translated(R.string.notification_channel_settings)) }
                        }
                        Note(context.translated(R.string.inbox_note))
                    }
                    2 -> {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(context.translated(R.string.nav_sync), context.translated(R.string.settings_filter), context.translated(R.string.settings_appearance), context.translated(R.string.settings_about)).forEachIndexed { index, title ->
                                FilterChip(selected = settingsPage == index, onClick = { settingsPage = index }, label = { Text(title) })
                            }
                        }
                        if (settingsPage == 0) Panel(context.translated(R.string.settings_sync)) {
                            Column(Modifier.selectableGroup()) {
                                SyncDirection.entries.forEach { value ->
                                    Choice(context.translated(value.title), session.direction == value, !session.busy) { session.configureDirection(value) }
                                }
                            }
                            Note(context.translated(R.string.direction_change_note))
                            Note(context.translated(R.string.direction_background_note))
                        }
                        if (settingsPage == 1) Panel(context.translated(R.string.regex_filter_title)) { FilterSettingsEditor(session) }
                        if (settingsPage == 2) Panel(context.translated(R.string.language_title)) {
                            Column(Modifier.selectableGroup()) {
                                AppLanguage.entries.forEach { language ->
                                    Choice(if (language == AppLanguage.SYSTEM) context.translated(R.string.theme_system) else language.nativeName,
                                        AppLanguages.selected(context) == language, context is ComponentActivity) {
                                        (context as? ComponentActivity)?.let { AppLanguages.select(it, language) }
                                    }
                                }
                            }
                        }
                        if (settingsPage == 2) Panel(context.translated(R.string.settings_appearance)) {
                            Column(Modifier.selectableGroup()) {
                                ThemeMode.entries.forEach { value ->
                                    Choice(context.translated(value.title), theme == value, true) { changeTheme(value) }
                                }
                            }
                        }
                        if (settingsPage == 0) Panel(context.translated(R.string.sensitive_content)) {
                            Column(Modifier.selectableGroup()) {
                                SensitivePolicy.entries.forEach { value ->
                                    Choice(context.translated(policyTitle(value)), policy == value, !session.busy) {
                                        if (value == SensitivePolicy.AUTO_WRITE) confirmAutoWrite = true
                                        else session.policy(value)
                                    }
                                }
                            }
                            Note(context.translated(R.string.unmarked_note))
                        }
                        if (settingsPage == 0) Panel(context.translated(R.string.background_execution)) {
                            SettingSwitch(context.translated(R.string.background_execution), context.translated(R.string.background_execution_note),
                                session.backgroundEnabled, !session.busy) { enabled ->
                                if (enabled) confirmBackgroundRun = true else session.configureBackgroundRunning(false)
                            }
                            Note(if (session.backgroundRunning) context.translated(R.string.background_running) else context.translated(R.string.listener_connect_first))
                            Note(context.translated(R.string.direction_status, context.translated(session.direction.title)))
                            if (session.backgroundEnabled && !session.notificationAuthorized) {
                                TextButton(onClick = ::requestNotificationPermission) { Text(context.translated(R.string.allow_notifications)) }
                            }
                            Note(context.translated(R.string.background_limits))
                        }
                        if (settingsPage == 0 && session.direction.sends) Panel(context.translated(R.string.shizuku_title)) {
                            Text(session.privilegedClipboard.status.resolve(context))
                            Note(context.translated(R.string.shizuku_note))
                            if (session.captureUnavailable) Note(context.translated(R.string.shizuku_capture_failed))
                            OutlinedButton(onClick = session.privilegedClipboard::requestPermission, modifier = Modifier.fillMaxWidth()) {
                                Text(context.translated(R.string.shizuku_authorize))
                            }
                            TextButton(onClick = { openProject("https://shizuku.rikka.app/download/") }) { Text(context.translated(R.string.shizuku_setup)) }
                        }
                        if (settingsPage == 3) Panel(context.translated(R.string.settings_about)) {
                            Text("RustClipSync ${BuildConfig.VERSION_NAME}")
                            Note(context.translated(R.string.build_revision, BuildConfig.BUILD_REVISION))
                            OutlinedButton(onClick = {
                                updateChecking = true
                                uiScope.launch {
                                    try { updateMessage = withContext(Dispatchers.IO) { ProjectUpdates.check(BuildConfig.VERSION_NAME) } }
                                    finally { updateChecking = false }
                                }
                            }, enabled = !updateChecking, modifier = Modifier.fillMaxWidth()) { Text(if (updateChecking) context.translated(R.string.checking_updates) else context.translated(R.string.check_updates)) }
                            updateMessage?.let { Text(it.resolve(context)) }
                            TextButton(onClick = { openProject(ProjectUpdates.RELEASES) }) { Text(context.translated(R.string.open_releases)) }
                            TextButton(onClick = { openProject(ProjectUpdates.PROJECT) }) { Text(context.translated(R.string.project_link)) }
                            TextButton(onClick = ::notificationSettings) { Text(context.translated(R.string.notification_settings)) }
                        }
                    }
                }
            }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(context.translated(R.string.delete_inbox_title)) }, text = { Text(context.translated(R.string.delete_inbox_warning)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; session.clearInbox() }) { Text(context.translated(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(context.translated(R.string.cancel)) } })
    if (confirmAutoWrite) AlertDialog(onDismissRequest = { confirmAutoWrite = false },
        title = { Text(context.translated(R.string.sensitive_write_title)) },
        text = { Text(context.translated(R.string.sensitive_write_warning)) },
        confirmButton = { TextButton(onClick = { confirmAutoWrite = false; session.policy(SensitivePolicy.AUTO_WRITE) }) { Text(context.translated(R.string.enable)) } },
        dismissButton = { TextButton(onClick = { confirmAutoWrite = false }) { Text(context.translated(R.string.cancel)) } })
    if (confirmBackgroundRun) AlertDialog(onDismissRequest = { confirmBackgroundRun = false },
        title = { Text(context.translated(R.string.background_execution_title)) },
        text = { Text(context.translated(R.string.background_execution_warning)) },
        confirmButton = { TextButton(onClick = { confirmBackgroundRun = false; session.configureBackgroundRunning(true); if (!session.notificationAuthorized) requestNotificationPermission() }) { Text(context.translated(R.string.enable)) } },
        dismissButton = { TextButton(onClick = { confirmBackgroundRun = false }) { Text(context.translated(R.string.cancel)) } })
    if (notificationRationale) AlertDialog(onDismissRequest = { notificationRationale = false },
        title = { Text(context.translated(R.string.notify_permission_title)) },
        text = { Text(context.translated(R.string.notification_permission_note)) },
        confirmButton = { TextButton(onClick = { notificationRationale = false; if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(context.translated(R.string.continue_permission)) } },
        dismissButton = { TextButton(onClick = { notificationRationale = false }) { Text(context.translated(R.string.not_now)) } })
}

private fun policyTitle(value: SensitivePolicy): Int = when (value) {
    SensitivePolicy.DISABLED -> R.string.policy_skip
    SensitivePolicy.ENCRYPTED_INBOX -> R.string.policy_inbox
    SensitivePolicy.AUTO_WRITE -> R.string.policy_direct_write
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
internal fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Choice(title: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, enabled = enabled, onClick = null)
        Text(title, Modifier.weight(1f).padding(start = 12.dp))
    }
}

@Composable
internal fun SettingSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title); Note(detail) }
        Switch(checked = checked, enabled = enabled, onCheckedChange = change)
    }
}
