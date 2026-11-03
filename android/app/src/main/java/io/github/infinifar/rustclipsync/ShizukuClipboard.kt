package io.github.infinifar.rustclipsync

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.infinifar.rustclipsync.bindings.AdapterException
import io.github.infinifar.rustclipsync.bindings.ClipboardText
import java.util.concurrent.FutureTask
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/** App-side lifecycle owner. Reading only happens on an authorized Rust adapter call. */
internal class ShizukuClipboard(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val io = ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, SynchronousQueue())
    private val args = Shizuku.UserServiceArgs(ComponentName(context, PrivilegedClipboardService::class.java))
        .tag("clipboard-source").processNameSuffix("clipboard_source").daemon(false).version(BuildConfig.VERSION_CODE)
    @Volatile private var remote: IPrivilegedClipboard? = null
    private var binding = false
    private var required = false
    var ready by mutableStateOf(false); private set
    var access by mutableStateOf(ShizukuAccessState.UNAVAILABLE); private set
    var status by mutableStateOf(UiMessage(R.string.shizuku_source_idle)); private set
    private val authorization = ShizukuAccess(
        probe = ::probeAccess,
        request = Shizuku::requestPermission,
        rationale = Shizuku::shouldShowRequestPermissionRationale,
        changed = { access = it },
    )
    var wake: (() -> Unit)? = null
    private val callback = object : IClipboardWake.Stub() {
        override fun changed() {
            if (Binder.getCallingUid() !in listOf(0, 2000)) return
            main.post { if (required && ready) { if (authorized()) wake?.invoke() else { stop(); refresh() } } }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (!required || !authorized() || service == null) { stop(); return }
            remote = IPrivilegedClipboard.Stub.asInterface(service)
            try { remote?.observe(callback); ready = true; status = UiMessage(R.string.shizuku_ready); wake?.invoke() }
            catch (_: Exception) { stop(); status = UiMessage(R.string.shizuku_failed) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { remote = null; binding = false; ready = false; status = UiMessage(if (required) R.string.shizuku_failed else R.string.shizuku_source_idle); wake?.invoke() }
    }
    init {
        Shizuku.addBinderReceivedListenerSticky { main.post { authorization.binderReceived(); refresh() } }
        Shizuku.addBinderDeadListener { main.post { authorization.binderDied(); stop(); wake?.invoke() } }
        Shizuku.addRequestPermissionResultListener { code, result -> main.post {
            if (authorization.result(code, result == PackageManager.PERMISSION_GRANTED)) refresh()
        } }
    }
    private fun probeAccess(): ShizukuAccessState = when {
        !Shizuku.pingBinder() || Shizuku.getVersion() < 13 -> ShizukuAccessState.UNAVAILABLE
        Shizuku.getUid() != 2000 -> ShizukuAccessState.SHELL_REQUIRED
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuAccessState.AUTHORIZED
        else -> ShizukuAccessState.NOT_AUTHORIZED
    }
    private fun authorized(): Boolean = try {
        access == ShizukuAccessState.AUTHORIZED && probeAccess() == ShizukuAccessState.AUTHORIZED
    } catch (_: RuntimeException) { false }

    fun requestPermission() {
        authorization.requestPermission()
        if (access == ShizukuAccessState.AUTHORIZED) refresh()
    }
    fun requireSource(value: Boolean) { required = value; if (value) refresh() else stop() }
    fun refresh() {
        authorization.refresh()
        if (access != ShizukuAccessState.AUTHORIZED) { stop(); return }
        status = UiMessage(when { ready -> R.string.shizuku_ready; required -> R.string.shizuku_source_connecting; else -> R.string.shizuku_source_idle })
        if (required && !binding) {
            try { binding = true; Shizuku.bindUserService(args, connection) }
            catch (_: RuntimeException) { binding = false; status = UiMessage(R.string.shizuku_failed) }
        }
    }
    private fun stop() {
        remote = null; ready = false; status = UiMessage(R.string.shizuku_source_idle)
        if (binding) { binding = false; try { Shizuku.unbindUserService(args, connection, true) } catch (_: RuntimeException) { } }
    }
    fun snapshot(): ClipboardText? {
        if (!authorized()) throw AdapterException.Unavailable()
        val service = remote ?: throw AdapterException.Unavailable()
        val task = FutureTask { service.snapshot() }
        try {
            io.execute(task)
            val data = task.get(1500, TimeUnit.MILLISECONDS)
            if (!authorized() || remote !== service) throw AdapterException.Unavailable()
            if (data.getBoolean("empty")) return null
            return ClipboardText(data.getString("text") ?: throw AdapterException.Unavailable(), data.getBoolean("sensitive"), data.getString("origin"))
        } catch (_: Exception) {
            task.cancel(true)
            main.post { stop(); status = UiMessage(R.string.shizuku_failed) }
            throw AdapterException.Unavailable()
        }
    }
}
