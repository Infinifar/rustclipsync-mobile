package io.github.infinifar.rustclipsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import io.github.infinifar.rustclipsync.bindings.AdapterException
import io.github.infinifar.rustclipsync.bindings.ClipboardText
import io.github.infinifar.rustclipsync.bindings.Platform
import io.github.infinifar.rustclipsync.bindings.MobileSession
import java.lang.ref.WeakReference

internal interface SessionPlatform : Platform {
    fun observe(session: MobileSession)
    fun focus(value: Boolean)
    fun backgroundWrite(value: Boolean)
    fun revoke()
}

internal class AndroidPlatform(context: Context, private val state: SecureState) : SessionPlatform {
    private val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
    private val gate = MainThreadGate()
    // Main-thread only. A foreground service does not grant clipboard access.
    private var focused = false
    private var allowBackgroundWrite = false
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null

    // Called on the UI thread; listener sends a counter only, never reads clipboard content.
    override fun observe(session: MobileSession) {
        val weakSession = WeakReference(session)
        listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (gate.isActive) weakSession.get()?.takeUnless { it.uniffiIsDestroyed }?.clipboardChanged()
        }
        clipboard.addPrimaryClipChangedListener(listener!!)
    }
    override fun revoke() {
        gate.revoke()
        listener?.let { clipboard.removePrimaryClipChangedListener(it) }
        listener = null
    }
    override fun focus(value: Boolean) { focused = value }
    override fun backgroundWrite(value: Boolean) { allowBackgroundWrite = value }
    override fun clipboardSnapshot(): ClipboardText? = gate.call {
        if (!focused) throw AdapterException.Unavailable()
        val clip = clipboard.primaryClip ?: return@call null
        if (clip.itemCount == 0) return@call null
        val value = clip.getItemAt(0).text?.toString() ?: return@call null
        if (value.toByteArray(Charsets.UTF_8).size > 10 * 1024 * 1024) throw AdapterException.Unavailable()
        val extras = clip.description.extras
        ClipboardText(value, extras?.getBoolean(SENSITIVE, false) ?: false, extras?.getString(ORIGIN))
    }
    override fun clipboardWrite(value: ClipboardText) = gate.call {
        if (!focused && !allowBackgroundWrite) throw AdapterException.Unavailable()
        val clip = ClipData.newPlainText("RustClipSync", value.text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(SENSITIVE, value.sensitive)
            value.origin?.let { putString(ORIGIN, it) }
        }
        clipboard.setPrimaryClip(clip)
    }
    override fun loadState(): ByteArray? = state.load()
    override fun saveState(ciphertext: ByteArray) = state.save(ciphertext)

    companion object {
        private const val SENSITIVE = "android.content.extra.IS_SENSITIVE"
        private const val ORIGIN = "io.github.infinifar.rustclipsync.ORIGIN"
    }
}
