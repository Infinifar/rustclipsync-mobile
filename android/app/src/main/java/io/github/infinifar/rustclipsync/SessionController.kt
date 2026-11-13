package io.github.infinifar.rustclipsync

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.infinifar.rustclipsync.bindings.FilterConfig
import io.github.infinifar.rustclipsync.bindings.TextFilterMode
import io.github.infinifar.rustclipsync.bindings.testFilter
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
    private val credentials: (Context) -> ConnectionStore = { SecureConnectionStore(it) },
) {
    private val application = context.applicationContext
    private val connectionStore by lazy { credentials(application) }
    private val preferences = application.getSharedPreferences("background-running-v2", Context.MODE_PRIVATE)
    private val syncPreferences = application.getSharedPreferences("sync-settings", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val notifications = InboxNotifications(application)
    internal val privilegedClipboard get() = (application as SyncApplication).privilegedClipboard
    internal var backgroundWake: (() -> Unit)? = null
    var captureUnavailable by mutableStateOf(false); private set
    private var native: MobileSession? = null
    private var platform: SessionPlatform? = null
    private var retiring: Job? = null
    private var epoch = 0L
    private var startingService = false
    private var focused = false
    private var selectedPolicy by mutableStateOf(SensitivePolicy.entries.firstOrNull {
        it.name == syncPreferences.getString("sensitive-policy", "DISABLED")
    } ?: SensitivePolicy.DISABLED)
    val policySelection get() = selectedPolicy
    var notificationAuthorized by mutableStateOf(InboxNotifications.authorized(application)); private set
    var foreground by mutableStateOf(false); private set
    var backgroundEnabled by mutableStateOf(preferences.getBoolean("enabled", false)); private set
    var backgroundRunning by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var paused by mutableStateOf(syncPreferences.getBoolean("paused", false)); private set
    var pauseUpdating by mutableStateOf(false); private set
    var connected by mutableStateOf(false); private set
    // Application-owned memory survives Activity/locale recreation; never use rememberSaveable for secrets.
    var connectionServer by mutableStateOf("")
    var connectionUsername by mutableStateOf("")
    var connectionPassword by mutableStateOf("")
    var credentialsBusy by mutableStateOf(true); private set
    var savedCredentials by mutableStateOf(false); private set
    var credentialLoadFailed by mutableStateOf(false); private set
    var credentialMessage by mutableStateOf<UiMessage?>(null); private set
    var status by mutableStateOf<SessionStatus?>(null); private set
    var direction by mutableStateOf(SyncDirection.entries.firstOrNull { it.key == syncPreferences.getString("direction", "both") } ?: SyncDirection.BOTH); private set
    var filterMode by mutableStateOf(TextFilterMode.entries.firstOrNull { it.name == syncPreferences.getString("filter-mode", "OFF") } ?: TextFilterMode.OFF); private set
    var filterRules by mutableStateOf(syncPreferences.getString("filter-rules", "") ?: ""); private set
    var filterDownloads by mutableStateOf(syncPreferences.getBoolean("filter-downloads", false)); private set
    fun filterConfig() = FilterConfig(filterMode, filterRules.lines().map { it.trim() }.filter { it.isNotEmpty() }, filterDownloads)

    // Native direction admission and generation invalidation remain serialized in the facade.
    fun configureDirection(value: SyncDirection) {
        if (value == direction || busy) return
        fun save() {
            direction = value
            syncPreferences.edit().putString("direction", value.key).apply()
            updateWriteAccess()
            if (backgroundRunning) application.startService(Intent(application, BackgroundSyncService::class.java).setAction(InboxNotifications.START))
        }
        if (connected) command {
            updateDirection(io.github.infinifar.rustclipsync.bindings.SyncDirection.valueOf(value.name))
            save()
            UiMessage(R.string.status_direction_changed)
        } else { save(); statusText = UiMessage(R.string.status_direction_changed) }
    }

    fun configureFilter(value: FilterConfig) {
        fun save() {
            filterMode = value.mode; filterRules = value.patterns.joinToString("\n"); filterDownloads = value.downloads
            syncPreferences.edit().putString("filter-mode", filterMode.name).putString("filter-rules", filterRules)
                .putBoolean("filter-downloads", filterDownloads).apply()
        }
        if (busy) return
        if (connected) command {
            try { updateFilter(value); save(); UiMessage(R.string.status_filter_updated) }
            catch (_: MobileException.InvalidInput) { UiMessage(R.string.filter_invalid_syntax) }
        }
        else scope.launch {
            busy = true
            try { withContext(Dispatchers.Default) { testFilter(value, "", false) }; save(); statusText = UiMessage(R.string.status_filter_saved) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                statusText = UiMessage(R.string.filter_invalid_limits)
            }
            finally { busy = false }
        }
    }

    val message: String get() = statusText.resolve(application)
    private var statusText by mutableStateOf(UiMessage(R.string.status_idle)); private set

    fun configurePause(value: Boolean) {
        if (pauseUpdating || (!connected && busy)) return
        fun save(selected: Boolean) {
            paused = selected
            syncPreferences.edit().putBoolean("paused", selected).apply()
            native?.clipboardChanged()
            updateWriteAccess()
            syncNotification()
            if (backgroundRunning) {
                try { application.startService(Intent(application, BackgroundSyncService::class.java).setAction(InboxNotifications.START)) }
                catch (_: RuntimeException) { /* Pausing still revokes local access. */ }
            }
        }
        if (value) save(true)
        val session = native
        if (!connected || session == null) {
            save(value)
            statusText = UiMessage(if (value) R.string.sync_paused else R.string.sync_resumed)
            return
        }
        val version = epoch
        pauseUpdating = true
        scope.launch {
            try {
                session.setPaused(value)
                if (version == epoch) {
                    save(value)
                    status = session.status()
                    statusText = UiMessage(if (value) R.string.sync_paused else R.string.sync_resumed)
                    backgroundWake?.invoke()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (version == epoch) statusText = safeError(error)
            } finally { pauseUpdating = false }
        }
    }

    init {
        scope.launch {
            try {
                val value = withContext(Dispatchers.IO) { connectionStore.load() }
                if (value != null) {
                    connectionServer = value.server; connectionUsername = value.username; connectionPassword = value.password
                    savedCredentials = true
                    credentialMessage = UiMessage(R.string.credentials_restored)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                credentialLoadFailed = true
                credentialMessage = UiMessage(R.string.credentials_load_failed)
            } finally { credentialsBusy = false }
        }
    }

    fun forgetCredentials() {
        if (busy || connected || credentialsBusy) return
        credentialsBusy = true
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { connectionStore.clear() }
                connectionServer = ""; connectionUsername = ""; connectionPassword = ""
                savedCredentials = false; credentialLoadFailed = false
                credentialMessage = UiMessage(R.string.credentials_deleted)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                credentialMessage = UiMessage(R.string.credentials_delete_failed)
            } finally { credentialsBusy = false; busy = false }
        }
    }

    fun connect(server: String, username: String, password: String, policy: SensitivePolicy) {
        if (busy || credentialsBusy || credentialLoadFailed || native != null || !foreground || !focused) return
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
                adapter.syncPaused(paused)
                adapter.focus(focused); adapter.observe(session)
                if (paused) session.setPaused(true)
                session.updateDirection(io.github.infinifar.rustclipsync.bindings.SyncDirection.valueOf(direction.name))
                session.updateFilter(filterConfig())
                session.check()
                val value = session.status()
                if (version == epoch) {
                    connectionServer = server; connectionUsername = username; connectionPassword = password
                    credentialsBusy = true
                    try {
                        withContext(Dispatchers.IO) { connectionStore.save(ConnectionCredentials(server, username, password)) }
                        savedCredentials = true
                        credentialMessage = UiMessage(R.string.credentials_saved)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        credentialMessage = UiMessage(R.string.credentials_save_failed)
                    } finally { credentialsBusy = false }
                }
                if (version == epoch) {
                    status = value; connected = true; statusText = UiMessage(R.string.status_connected)
                    startBackgroundIfEnabled()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (version == epoch) { close(); statusText = safeError(error) }
            } finally { if (version == epoch) { busy = false; backgroundWake?.invoke() } }
        }
    }

    fun uiVisible(value: Boolean) {
        foreground = value
        updateWriteAccess()
        native?.clipboardChanged() // Invalidate an in-flight receive across a lifecycle transition.
        if (value) {
            notifications.mask(); refreshNotificationAuthorization(); privilegedClipboard.refreshPermission(); startBackgroundIfEnabled()
            // Recreated activities also refresh the running FGS title after a per-app locale change.
            if (backgroundRunning) {
                try { application.startService(Intent(application, BackgroundSyncService::class.java).setAction(InboxNotifications.START)) }
                catch (_: RuntimeException) { /* The existing service stays bounded by its system lifetime. */ }
            }
        }
        else {
            clipboardFocus(false)
            if (!backgroundEnabled || (!backgroundRunning && !startingService)) close()
            else { syncNotification(); backgroundWake?.invoke() }
        }
    }

    fun clipboardFocus(value: Boolean) {
        focused = value && foreground
        platform?.focus(focused)
        native?.clipboardChanged()
        if (focused) backgroundWake?.invoke()
    }

    fun configureBackgroundRunning(value: Boolean) {
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

    private fun updateWriteAccess() {
        platform?.syncPaused(paused)
        platform?.backgroundRead(!paused && backgroundEnabled && backgroundRunning && direction.sends && !foreground)
        platform?.backgroundWrite(!paused && backgroundEnabled && backgroundRunning && direction.receives && !foreground)
        privilegedClipboard.requireSource(!paused && backgroundEnabled && backgroundRunning && direction.sends && !foreground)
        privilegedClipboard.wake = { native?.clipboardChanged(); backgroundWake?.invoke() }
    }

    private fun startBackgroundIfEnabled() {
        if (!backgroundEnabled || !connected || !foreground || backgroundRunning || startingService) return
        startingService = true
        try { application.startForegroundService(Intent(application, BackgroundSyncService::class.java).setAction(InboxNotifications.START)) }
        catch (_: RuntimeException) {
            startingService = false
            statusText = UiMessage(R.string.status_service_denied)
            if (!foreground) close()
        }
    }

    fun serviceStarted(): Boolean {
        startingService = false
        backgroundRunning = backgroundEnabled && connected
        updateWriteAccess()
        syncNotification()
        return backgroundRunning
    }

    fun serviceStopped() {
        startingService = false; backgroundRunning = false
        updateWriteAccess()
        notifications.clear()
        if (!foreground) close()
    }

    fun serviceTimedOut() {
        // End this run without changing the user's persistent opt-in or restarting in a loop.
        close()
        statusText = UiMessage(R.string.status_service_timeout)
    }

    fun notificationEligible(): Boolean = !paused && direction.receives && backgroundEnabled && backgroundRunning && connected && selectedPolicy == SensitivePolicy.ENCRYPTED_INBOX

    val inboxNotificationReady: Boolean get() = notificationEligible() && notificationAuthorized && status?.inbox != null
    val inboxNotificationHint: String get() = when {
        paused -> application.translated(R.string.sync_paused)
        !connected -> application.translated(R.string.hint_connect_notification)
        !direction.receives -> application.translated(R.string.status_upload_only)
        !backgroundEnabled || !backgroundRunning -> application.translated(R.string.hint_start_listening)
        selectedPolicy != SensitivePolicy.ENCRYPTED_INBOX -> application.translated(R.string.hint_wrong_policy)
        !notificationAuthorized -> application.translated(R.string.hint_notification_denied)
        status?.inbox == null -> application.translated(R.string.hint_empty_inbox)
        else -> application.translated(R.string.hint_inbox_ready)
    }

    private fun syncNotification() = notifications.sync(status?.inbox?.id, notificationEligible())
    fun showInboxNotification() {
        refreshNotificationAuthorization()
        val id = status?.inbox?.id ?: return
        if (inboxNotificationReady) notifications.offer(id)
    }

    fun refreshNotificationAuthorization() {
        notificationAuthorized = InboxNotifications.authorized(application)
        notifications.refreshAuthorization()
        syncNotification()
    }

    /** A scheduling tick, not an upload retry loop. Core owns all receive/ack rules. */
    suspend fun pollBackground() {
        if (paused || pauseUpdating) return
        refreshNotificationAuthorization()
        privilegedClipboard.refresh()
        when (SyncSchedule.tick(backgroundEnabled, backgroundRunning, connected, busy, foreground, focused, direction.receives)) {
            SyncSchedule.Tick.NONE -> return
            SyncSchedule.Tick.FOREGROUND_RECEIVE -> {
                perform {
                    if (this@SessionController.status?.hasPending == true) retryPending()
                    UiMessage.outcome(receive())
                }
                return
            }
            SyncSchedule.Tick.BACKGROUND -> Unit
        }
        perform {
            val source = if (direction.sends && privilegedClipboard.ready)
                io.github.infinifar.rustclipsync.bindings.BackgroundClipboardSource.PRIVILEGED
                else io.github.infinifar.rustclipsync.bindings.BackgroundClipboardSource.PENDING_ONLY
            val result = syncBackground(source)
            captureUnavailable = result.capture == io.github.infinifar.rustclipsync.bindings.BackgroundCapture.UNAVAILABLE
            UiMessage(R.string.status_background_sync, listOf(UiMessage.outcome(result.sent), UiMessage.outcome(result.received)))
        }
    }

    suspend fun notificationAction(intent: Intent) = notifications.action(intent, ::notificationEligible) { id ->
        val session = native ?: return@action null
        val version = epoch
        val text = session.readInbox(id)
        if (version == epoch && status?.inbox?.id == id) text else null
    }

    suspend fun notificationCopy(intent: Intent) {
        if (!notificationEligible()) return
        val permit = notifications.claimCopy(intent) ?: return
        val id = permit.id
        val version = epoch
        repeat(80) { if (busy && version == epoch) delay(25) }
        if (busy || version != epoch || !notificationEligible() || notifications.claimCopy(intent) != permit || status?.inbox?.id != id) return
        val adapter = platform ?: return
        // One explicit operation, bound to this item. Recheck authorization,
        // lifecycle, current ticket and lock state inside the main-thread write.
        adapter.explicitCopy(id) { version == epoch && notificationEligible() && notifications.claimCopy(intent) == permit }
        try { perform { copyInbox(id); notifications.mask(); UiMessage(R.string.status_copied) } }
        finally { adapter.explicitCopy(null) { false } }
    }

    fun send() { if (!paused && focused && direction.sends) command { UiMessage.outcome(sendClipboard()) } }
    fun receive() { if (!paused && focused && direction.receives) command { UiMessage.outcome(receive()) } }
    fun retry() { if (!paused && focused) command { UiMessage.outcome(retryPending()) } }
    fun policy(value: SensitivePolicy) {
        if (busy) return
        fun save() {
            selectedPolicy = value
            syncPreferences.edit().putString("sensitive-policy", value.name).apply()
            notifications.clear()
        }
        if (connected) command { updatePolicy(value); save(); UiMessage(R.string.status_policy_saved) }
        else { save(); statusText = UiMessage(R.string.status_policy_saved) }
    }
    fun copyInbox() {
        val id = status?.inbox?.id ?: return
        if (focused) command {
            val version = epoch
            val adapter = platform
            adapter?.explicitCopy(id) { version == epoch && focused && this@SessionController.status?.inbox?.id == id }
            try { copyInbox(id); UiMessage(R.string.status_copied) }
            finally { adapter?.explicitCopy(null) { false } }
        }
    }
    fun clearInbox() { val id = status?.inbox?.id ?: return; command { clearInbox(id); notifications.clear(); UiMessage(R.string.status_inbox_cleared) } }
    private fun command(operation: suspend MobileSession.() -> UiMessage) { scope.launch {
        perform(operation)
        if (!foreground && backgroundRunning) backgroundWake?.invoke()
    } }
    private suspend fun perform(operation: suspend MobileSession.() -> UiMessage) {
        val session = native ?: return
        if (busy || !connected) return
        val version = epoch
        busy = true
        try {
            val result = session.operation(); val value = session.status()
            if (version == epoch) { statusText = result; status = value }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (version == epoch) {
                if (error is MobileException.Storage || error is MobileException.Closed || error is MobileException.Internal) close()
                else {
                    val value = try { session.status() } catch (_: Exception) { null }
                    if (version != epoch) return
                    status = value
                }
                statusText = safeError(error)
            }
        } finally { if (version == epoch) { busy = false; syncNotification() } }
    }

    fun close() {
        ++epoch
        startingService = false; backgroundRunning = false
        notifications.clear()
        privilegedClipboard.requireSource(false)
        application.stopService(Intent(application, BackgroundSyncService::class.java))
        platform?.revoke(); platform = null
        val session = native; native = null
        connected = false; busy = false; status = null; statusText = UiMessage(R.string.status_closed)
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
    private fun safeError(error: Exception): UiMessage = UiMessage(when (error) {
        is MobileException.Closed -> R.string.error_closed
        is MobileException.Busy -> R.string.error_busy
        is MobileException.InvalidInput -> R.string.error_invalid
        is MobileException.Storage -> R.string.error_storage
        is MobileException.Clipboard -> R.string.error_clipboard
        is MobileException.Pairing -> R.string.error_pairing
        is MobileException.Conflict -> R.string.error_conflict
        is MobileException.Expired -> R.string.error_expired
        is MobileException.Network -> R.string.error_network
        is MobileException.Protocol -> R.string.error_protocol
        is MobileException.ProtocolUnsupported -> R.string.error_protocol_unsupported
        is MobileException.Internal -> R.string.error_internal
        else -> R.string.error_platform
    })
}
