package io.github.infinifar.rustclipsync

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.infinifar.rustclipsync.bindings.MobileConfig
import io.github.infinifar.rustclipsync.bindings.MobileException
import io.github.infinifar.rustclipsync.bindings.MobileSession
import io.github.infinifar.rustclipsync.bindings.Platform
import io.github.infinifar.rustclipsync.bindings.SensitivePolicy
import io.github.infinifar.rustclipsync.bindings.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One process owner for UI and service; all entry points and observable state stay on main. */
internal class SessionController(
    context: Context,
    private val resources: (Context) -> Pair<SessionPlatform, ByteArray> = {
        val store = SecureState(it); AndroidPlatform(it, store) to store.key()
    },
    private val open: (MobileConfig, Platform) -> MobileSession = { config, adapter -> MobileSession(config, adapter) },
) {
    private val application = context.applicationContext
    private val preferences = application.getSharedPreferences("background-listening", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val notifications = InboxNotifications(application)
    private var native: MobileSession? = null
    private var platform: SessionPlatform? = null
    private var retiring: Job? = null
    private var epoch = 0L
    private var startingService = false
    private var focused = false
    private var selectedPolicy by mutableStateOf(SensitivePolicy.DISABLED)
    val policySelection get() = selectedPolicy
    var notificationAuthorized by mutableStateOf(InboxNotifications.authorized(application)); private set
    var foreground by mutableStateOf(false); private set
    var backgroundEnabled by mutableStateOf(preferences.getBoolean("enabled", false)); private set
    var backgroundWritesEnabled by mutableStateOf(preferences.getBoolean("write", false)); private set
    var backgroundRunning by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var connected by mutableStateOf(false); private set
    var status by mutableStateOf<SessionStatus?>(null); private set
    var message by mutableStateOf("仅在前台手动同步"); private set

    fun connect(server: String, username: String, password: String, policy: SensitivePolicy) {
        if (busy || native != null || !foreground || !focused) return
        val version = ++epoch
        selectedPolicy = policy
        busy = true
        scope.launch {
            try {
                retiring?.join()
                val (adapter, key) = withContext(Dispatchers.IO) { resources(application) }
                if (version != epoch) { key.fill(0); adapter.revoke(); return@launch }
                val session = try { open(MobileConfig(server, username, password, key, policy), adapter) }
                    finally { key.fill(0) }
                platform = adapter; native = session
                adapter.focus(focused); adapter.observe(session)
                session.check()
                val value = session.status()
                if (version == epoch) {
                    status = value; connected = true; message = "已连接"
                    startBackgroundIfEnabled()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (version == epoch) { close(); message = safeError(error) }
            } finally { if (version == epoch) busy = false }
        }
    }

    fun uiVisible(value: Boolean) {
        foreground = value
        updateWriteAccess()
        native?.clipboardChanged() // Invalidate an in-flight receive across a lifecycle transition.
        if (value) { notifications.clear(); refreshNotificationAuthorization(); startBackgroundIfEnabled() }
        else {
            clipboardFocus(false)
            if (!backgroundEnabled || (!backgroundRunning && !startingService)) close()
        }
    }

    fun clipboardFocus(value: Boolean) {
        focused = value && foreground
        platform?.focus(focused)
        native?.clipboardChanged()
    }

    fun configureBackgroundListening(value: Boolean) {
        backgroundEnabled = value
        native?.clipboardChanged()
        updateWriteAccess()
        preferences.edit().putBoolean("enabled", value).apply()
        if (value) startBackgroundIfEnabled() else {
            startingService = false; backgroundRunning = false
            notifications.clear()
            application.stopService(Intent(application, BackgroundSyncService::class.java))
            if (!foreground) close()
        }
    }

    fun configureBackgroundWrites(value: Boolean) {
        backgroundWritesEnabled = value
        preferences.edit().putBoolean("write", value).apply()
        native?.clipboardChanged()
        updateWriteAccess()
        if (backgroundRunning && foreground) application.startService(Intent(application, BackgroundSyncService::class.java).setAction(InboxNotifications.START))
    }

    private fun updateWriteAccess() {
        platform?.backgroundWrite(backgroundEnabled && backgroundRunning && backgroundWritesEnabled && !foreground)
    }

    private fun startBackgroundIfEnabled() {
        if (!backgroundEnabled || !connected || !foreground || backgroundRunning || startingService) return
        startingService = true
        try { application.startForegroundService(Intent(application, BackgroundSyncService::class.java).setAction(InboxNotifications.START)) }
        catch (_: RuntimeException) {
            startingService = false
            message = "系统未允许启动后台监听；请在前台重试"
            if (!foreground) close()
        }
    }

    fun serviceStarted(): Boolean {
        startingService = false
        backgroundRunning = backgroundEnabled && connected
        updateWriteAccess()
        return backgroundRunning
    }

    fun serviceStopped() {
        startingService = false; backgroundRunning = false
        updateWriteAccess()
        notifications.clear()
        if (!foreground) close()
    }

    fun serviceTimedOut() {
        configureBackgroundListening(false)
        message = "后台监听已达到系统时限并停止；可回到前台重新启用"
    }

    fun notificationEligible(): Boolean = backgroundEnabled && backgroundRunning && connected && !foreground && selectedPolicy == SensitivePolicy.ENCRYPTED_INBOX

    fun refreshNotificationAuthorization() {
        notificationAuthorized = InboxNotifications.authorized(application)
        notifications.refreshAuthorization()
    }

    /** A scheduling tick, not an upload retry loop. Core owns all receive/ack rules. */
    suspend fun pollInbox() {
        refreshNotificationAuthorization()
        if (!backgroundEnabled || !backgroundRunning || !connected || foreground || busy) return
        if (!backgroundWritesEnabled && selectedPolicy != SensitivePolicy.ENCRYPTED_INBOX) return
        val previous = status?.inbox?.id
        perform { receiveBackground(backgroundWritesEnabled); "后台接收已检查" }
        val id = status?.inbox?.id
        if (notificationEligible() && id != null && id != previous) notifications.offer(id)
    }

    suspend fun notificationAction(intent: Intent) = notifications.action(intent, ::notificationEligible) { id ->
        val session = native ?: return@action null
        val version = epoch
        val text = session.readInbox(id)
        if (version == epoch && status?.inbox?.id == id) text else null
    }

    data class CopyRequest(val id: String, val epoch: Long)
    fun claimNotificationCopy(intent: Intent): CopyRequest? {
        if (!notificationEligible()) return null
        return notifications.claimCopy(intent)?.let { CopyRequest(it, epoch) }
    }

    suspend fun copyFromNotification(request: CopyRequest): Boolean {
        // A receive may still be completing. Yield rather than block the UI thread.
        repeat(80) { if (busy && request.epoch == epoch) delay(25) }
        if (busy || !foreground || !focused || request.epoch != epoch || selectedPolicy != SensitivePolicy.ENCRYPTED_INBOX || status?.inbox?.id != request.id) return false
        var copied = false
        perform { copyInbox(request.id); copied = true; "已显式复制敏感内容" }
        return copied && request.epoch == epoch
    }

    fun send() { if (focused) command { sendClipboard().toString() } }
    fun receive() { if (focused) command { receive().toString() } }
    fun retry() { if (focused) command { retryPending().toString() } }
    fun policy(value: SensitivePolicy) = command {
        updatePolicy(value); selectedPolicy = value; notifications.clear(); "敏感策略已更新"
    }
    fun copyInbox() {
        val id = status?.inbox?.id ?: return
        if (focused) command { copyInbox(id); "已显式复制敏感内容" }
    }
    fun clearInbox() { val id = status?.inbox?.id ?: return; command { clearInbox(id); notifications.clear(); "收件箱已清空" } }
    private fun command(operation: suspend MobileSession.() -> String) { scope.launch { perform(operation) } }
    private suspend fun perform(operation: suspend MobileSession.() -> String) {
        val session = native ?: return
        if (busy || !connected) return
        val version = epoch
        busy = true
        try {
            val result = session.operation(); val value = session.status()
            if (version == epoch) { message = result; status = value }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (version == epoch) {
                if (error is MobileException.Storage || error is MobileException.Closed || error is MobileException.Internal) close()
                else {
                    val value = try { session.status() } catch (_: Exception) { null }
                    if (version != epoch) return
                    status = value
                }
                message = safeError(error)
            }
        } finally { if (version == epoch) busy = false }
    }

    fun close() {
        ++epoch
        startingService = false; backgroundRunning = false
        notifications.clear()
        application.stopService(Intent(application, BackgroundSyncService::class.java))
        platform?.revoke(); platform = null
        val session = native; native = null
        connected = false; busy = false; status = null; message = "已关闭；回到前台后可重新连接"
        if (session != null) {
            session.requestClose()
            val previous = retiring
            retiring = cleanup.launch {
                previous?.join()
                try { session.shutdown() }
                catch (_: Exception) { /* Revoked; still release the binding. */ }
                finally { session.destroy() }
            }
        }
    }
    private fun safeError(error: Exception): String = when (error) {
        is MobileException -> error.message ?: "同步失败"
        else -> "平台或安全存储不可用；请检查配置后重试"
    }
}
