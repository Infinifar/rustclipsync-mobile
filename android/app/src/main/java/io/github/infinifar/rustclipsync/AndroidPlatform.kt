package io.github.infinifar.rustclipsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import io.github.infinifar.rustclipsync.bindings.AdapterException
import io.github.infinifar.rustclipsync.bindings.ClipboardText
import io.github.infinifar.rustclipsync.bindings.Platform
import io.github.infinifar.rustclipsync.bindings.MobileSession
import java.lang.ref.WeakReference
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal class AndroidPlatform(context: Context, private val state: SecureState) : Platform {
    private val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(true)
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    // Called on the UI thread; listener sends a counter only, never reads clipboard content.
    fun observe(session: MobileSession) {
        val weakSession = WeakReference(session)
        listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (active.get()) weakSession.get()?.takeUnless { it.uniffiIsDestroyed }?.clipboardChanged()
        }
        clipboard.addPrimaryClipChangedListener(listener!!)
    }
    fun revoke() {
        active.set(false)
        listener?.let { clipboard.removePrimaryClipChangedListener(it) }
        listener = null
    }
    override fun clipboardSnapshot(): ClipboardText? = onMain {
        val clip = clipboard.primaryClip ?: return@onMain null
        if (clip.itemCount == 0) return@onMain null
        val value = clip.getItemAt(0).text?.toString() ?: return@onMain null
        if (value.toByteArray(Charsets.UTF_8).size > 10 * 1024 * 1024) throw AdapterException.Unavailable()
        val extras = clip.description.extras
        ClipboardText(value, extras?.getBoolean(SENSITIVE, false) ?: false, extras?.getString(ORIGIN))
    }
    override fun clipboardWrite(value: ClipboardText) = onMain {
        val clip = ClipData.newPlainText("RustClipSync", value.text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(SENSITIVE, value.sensitive)
            value.origin?.let { putString(ORIGIN, it) }
        }
        clipboard.setPrimaryClip(clip)
    }
    override fun loadState(): ByteArray? = state.load()
    override fun saveState(ciphertext: ByteArray) = state.save(ciphertext)

    private fun <T> onMain(operation: () -> T): T {
        val deadline = SystemClock.elapsedRealtime() + 2_000
        val task = FutureTask {
            if (!active.get() || SystemClock.elapsedRealtime() >= deadline) throw AdapterException.Unavailable()
            operation()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
        return try { task.get(2, TimeUnit.SECONDS) }
        catch (_: TimeoutException) { task.cancel(false); throw AdapterException.Timeout() }
        catch (_: Exception) { task.cancel(false); throw AdapterException.Unavailable() }
    }
    companion object {
        private const val SENSITIVE = "android.content.extra.IS_SENSITIVE"
        private const val ORIGIN = "io.github.infinifar.rustclipsync.ORIGIN"
    }
}
