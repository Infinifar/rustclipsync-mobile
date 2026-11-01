package io.github.infinifar.rustclipsync

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.infinifar.rustclipsync.bindings.MobileConfig
import io.github.infinifar.rustclipsync.bindings.MobileException
import io.github.infinifar.rustclipsync.bindings.MobileSession
import io.github.infinifar.rustclipsync.bindings.SensitivePolicy
import io.github.infinifar.rustclipsync.bindings.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI owns this wrapper; generated objects and cleanup stay here. All entry points are main-thread. */
internal class SessionController(context: Context) {
    private val application = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var native: MobileSession? = null
    private var platform: AndroidPlatform? = null
    private var retiring: Job? = null
    private var epoch = 0L
    var busy by mutableStateOf(false); private set
    var connected by mutableStateOf(false); private set
    var status by mutableStateOf<SessionStatus?>(null); private set
    var message by mutableStateOf("仅在前台手动同步"); private set

    fun connect(server: String, username: String, password: String, policy: SensitivePolicy) {
        if (busy || native != null) return
        val version = ++epoch
        busy = true
        scope.launch {
            try {
                // A previous executor must release its store before the same state can reopen.
                retiring?.join()
                val (store, key) = withContext(Dispatchers.IO) { val store = SecureState(application); store to store.key() }
                if (version != epoch) { key.fill(0); return@launch }
                val adapter = AndroidPlatform(application, store)
                val session = try { MobileSession(MobileConfig(server, username, password, key, policy), adapter) }
                    finally { key.fill(0) }
                platform = adapter; native = session
                adapter.observe(session)
                session.check()
                val value = session.status()
                if (version == epoch) { status = value; connected = true; message = "已连接" }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (version == epoch) { close(); message = safeError(error) }
            } finally { if (version == epoch) busy = false }
        }
    }
    fun send() = command { sendClipboard().toString() }
    fun receive() = command { receive().toString() }
    fun retry() = command { retryPending().toString() }
    fun policy(value: SensitivePolicy) = command { updatePolicy(value); "敏感策略已更新" }
    fun copyInbox() { val id = status?.inbox?.id ?: return; command { copyInbox(id); "已显式复制敏感内容" } }
    fun clearInbox() { val id = status?.inbox?.id ?: return; command { clearInbox(id); "收件箱已清空" } }
    private fun command(operation: suspend MobileSession.() -> String) {
        val session = native ?: return
        if (busy || !connected) return
        val version = epoch
        busy = true
        scope.launch {
            try {
                val result = session.operation(); val value = session.status()
                if (version == epoch) { message = result; status = value }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (version == epoch) {
                    if (error is MobileException.Storage || error is MobileException.Closed || error is MobileException.Internal) close()
                    else {
                        val value = try { session.status() } catch (_: Exception) { null }
                        if (version != epoch) return@launch
                        status = value
                    }
                    message = safeError(error)
                }
            } finally { if (version == epoch) busy = false }
        }
    }
    fun close() {
        ++epoch
        platform?.revoke(); platform = null
        val session = native; native = null
        connected = false; busy = false; status = null; message = "已关闭；回到前台后可重新连接"
        if (session != null) {
            session.requestClose()
            val previous = retiring
            retiring = cleanup.launch {
                previous?.join()
                try { session.shutdown() }
                catch (_: Exception) { /* Already revoked; a failed executor must still release its binding. */ }
                finally { session.destroy() }
            }
        }
    }
    private fun safeError(error: Exception): String = when (error) {
        is MobileException -> error.message ?: "同步失败"
        else -> "平台或安全存储不可用；请检查配置后重试"
    }
}
