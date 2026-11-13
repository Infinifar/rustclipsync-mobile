package io.github.infinifar.rustclipsync

import android.content.ComponentName
import android.content.Context
import android.os.Binder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.darken.porter.sdk.PermissionState
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.PorterBackend
import eu.darken.porter.sdk.PorterConnection
import eu.darken.porter.sdk.PorterConnectionState
import eu.darken.porter.sdk.PorterException
import eu.darken.porter.sdk.UserServiceArgs
import io.github.infinifar.rustclipsync.bindings.AdapterException
import io.github.infinifar.rustclipsync.bindings.ClipboardText
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.FutureTask
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** App-side lifecycle owner for both managers. All UI state is changed on main. */
internal class PrivilegedClipboard(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, SynchronousQueue())
    private val args = UserServiceArgs(
        componentName = ComponentName(context, PrivilegedClipboardService::class.java),
        tag = "clipboard-source",
        processNameSuffix = "clipboard_source",
        version = BuildConfig.VERSION_CODE,
    )

    private class Source(val connection: PorterConnection, val service: IPrivilegedClipboard)
    @Volatile private var remote: Source? = null
    private var binding: Job? = null
    private var bindingConnection: PorterConnection? = null
    private var request: Job? = null
    private var denied = false
    private var required = false
    var ready by mutableStateOf(false); private set
    var canRequest by mutableStateOf(false); private set
    var access by mutableStateOf(UiMessage(R.string.clipboard_access_unavailable)); private set
    var status by mutableStateOf(UiMessage(R.string.clipboard_source_idle)); private set
    var wake: (() -> Unit)? = null

    init {
        scope.launch {
            Porter.state.collectLatest { state ->
                request?.cancel()
                denied = false
                refresh()
                if (state is PorterConnectionState.Connected) {
                    state.connection.permission.collect { refresh() }
                }
            }
        }
    }

    fun requestPermission() {
        val connection = Porter.connection.value ?: return
        if (!canRequest || !supported(connection)) return
        request = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val permission = withTimeoutOrNull(IPC_TIMEOUT_MS) { connection.checkPermission() }
                if (permission is PermissionState.Denied && !permission.permanentlyDenied) {
                    val result = connection.requestPermission()
                    if (Porter.connection.value === connection) denied = result is PermissionState.Denied
                }
            } catch (_: PorterException) {
                // Connection changes and revocations are reported through Porter.state/permission.
            } finally {
                request = null
                refresh()
            }
        }
        request?.start()
        refresh()
    }

    fun refreshPermission() {
        val connection = Porter.connection.value ?: return
        if (!supported(connection) || request != null) return
        scope.launch {
            try { withTimeoutOrNull(IPC_TIMEOUT_MS) { connection.checkPermission() } }
            catch (_: PorterException) { /* A new connection will publish its own state. */ }
            refresh()
        }
    }

    fun requireSource(value: Boolean) {
        required = value
        refresh()
    }

    fun refresh() {
        val state = Porter.state.value
        val connection = (state as? PorterConnectionState.Connected)?.connection
        val permission = connection?.permission?.value
        val manager = listOf(connection?.backend?.let(::managerName).orEmpty())
        canRequest = connection != null && supported(connection) &&
            permission is PermissionState.Denied && !permission.permanentlyDenied && request == null
        access = when {
            state is PorterConnectionState.Incompatible -> UiMessage(
                if (state.incompatibility.serverTooOld) R.string.clipboard_manager_update else R.string.clipboard_client_update,
                listOf(managerName(state.incompatibility.backend)),
            )
            connection == null -> UiMessage(R.string.clipboard_access_unavailable)
            connection.uid != SHELL_UID -> UiMessage(R.string.clipboard_shell_required, manager)
            !supported(connection) -> UiMessage(R.string.clipboard_manager_update, manager)
            permission is PermissionState.Granted -> UiMessage(R.string.clipboard_access_granted, manager)
            request != null -> UiMessage(R.string.clipboard_access_requesting, manager)
            permission is PermissionState.Denied && (permission.permanentlyDenied || denied) ->
                UiMessage(R.string.clipboard_access_denied, manager)
            else -> UiMessage(R.string.clipboard_access_needed, manager)
        }
        if (!required || connection == null || !authorized(connection)) {
            stop()
            return
        }
        if (binding != null) {
            if (bindingConnection !== connection) stop()
            return
        }

        status = UiMessage(R.string.clipboard_source_connecting)
        bindingConnection = connection
        binding = scope.launch(start = CoroutineStart.LAZY) {
            try {
                connection.userService(args).collect { binder ->
                    if (!required || !authorized(connection)) return@collect
                    val source = Source(connection, IPrivilegedClipboard.Stub.asInterface(binder))
                    val callback = object : IClipboardWake.Stub() {
                        override fun changed() {
                            if (Binder.getCallingUid() != SHELL_UID) return
                            scope.launch {
                                if (remote === source && authorized(connection)) wake?.invoke()
                            }
                        }
                    }
                    withContext(Dispatchers.IO) { call { source.service.observe(callback) } }
                    if (required && authorized(connection)) {
                        remote = source
                        ready = true
                        status = UiMessage(R.string.clipboard_source_ready)
                        wake?.invoke()
                    }
                }
            } catch (_: PorterException) {
                // The service or its authorization went away. A later tick can rebind.
            } catch (_: AdapterException) {
                // The clipboard service did not answer within the IPC deadline.
            } finally {
                clearSource()
                // A cancelled SDK collection only unbinds. Explicitly stop the shell process.
                withContext(NonCancellable) {
                    withTimeoutOrNull(IPC_TIMEOUT_MS) {
                        try { connection.stopUserService(args) }
                        catch (_: PorterException) { /* Server exit or revocation also stops it. */ }
                    }
                }
                bindingConnection = null
                binding = null
                status = UiMessage(if (required) R.string.clipboard_source_failed else R.string.clipboard_source_idle)
            }
        }
        binding?.start()
    }

    private fun stop() {
        clearSource()
        binding?.cancel()
        status = UiMessage(R.string.clipboard_source_idle)
    }

    private fun clearSource() {
        remote = null
        if (ready) {
            ready = false
            wake?.invoke()
        }
    }

    fun snapshot(): ClipboardText? {
        val source = remote ?: throw AdapterException.Unavailable()
        if (!authorized(source.connection)) throw AdapterException.Unavailable()
        val data = try { call { source.service.snapshot() } }
        catch (error: AdapterException) {
            scope.launch { if (remote === source) stop() }
            throw error
        }
        if (remote !== source || !authorized(source.connection)) throw AdapterException.Unavailable()
        if (data.getBoolean("empty")) return null
        return ClipboardText(
            data.getString("text") ?: throw AdapterException.Unavailable(),
            data.getBoolean("sensitive"),
            data.getString("origin"),
        )
    }

    private fun <T> call(operation: () -> T): T {
        val task = FutureTask(operation)
        try {
            io.execute(task)
            return task.get(IPC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            task.cancel(true)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw AdapterException.Unavailable()
        }
    }

    private fun authorized(connection: PorterConnection): Boolean =
        Porter.connection.value === connection && connection.binder.isBinderAlive &&
            supported(connection) && connection.permission.value is PermissionState.Granted

    private fun supported(connection: PorterConnection): Boolean =
        connection.uid == SHELL_UID &&
            (connection.backend != PorterBackend.SHIZUKU || connection.serverInfo.version >= 13)

    private fun managerName(backend: PorterBackend) = when (backend) {
        PorterBackend.PORTER -> "Porter"
        PorterBackend.SHIZUKU -> "Shizuku"
    }

    private companion object {
        const val SHELL_UID = 2000
        const val IPC_TIMEOUT_MS = 1_500L
    }
}
