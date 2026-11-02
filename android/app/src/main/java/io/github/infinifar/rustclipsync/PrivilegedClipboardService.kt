package io.github.infinifar.rustclipsync

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import androidx.annotation.Keep
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shizuku UserService: clipboard-only RPC, no shell execution, credentials, storage or networking. */
@Keep
class PrivilegedClipboardService @Keep constructor(context: Context) : IPrivilegedClipboard.Stub() {
    private val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
    private val power = context.getSystemService(android.os.PowerManager::class.java)
    private var failed = false
    private val ownerUid = context.applicationInfo.uid.also {
        check(Process.myUid() == 2000) { "ADB Shizuku mode required" }
    }
    private val userId = ownerUid / 100000
    private val monitor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var callback: IClipboardWake? = null
    private var started = false
    private var fingerprint: ByteArray? = null

    private fun requireOwner() {
        if (Binder.getCallingUid() != ownerUid) throw SecurityException("Private clipboard bridge")
    }

    // Reflection stays inside the shell/root process, where Shizuku permits non-SDK APIs.
    // Select signatures by type/arity rather than hardcoding changing Binder transaction numbers.
    @SuppressLint("PrivateApi") // Runs only in an authorized Shizuku shell UserService, never the app process.
    private fun readClipboard(): Bundle {
        if (keyguard.isDeviceLocked || !power.isInteractive) throw RemoteException("Device locked")
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
        val stub = Class.forName("android.content.IClipboard\$Stub")
        val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val method = Class.forName("android.content.IClipboard").methods.firstOrNull {
            it.name == "getPrimaryClip" && it.parameterTypes.size in 3..4 && it.parameterTypes.take(2).all { type -> type == String::class.java }
        } ?: throw RemoteException("Unsupported clipboard interface")
        val args = if (method.parameterTypes.size == 4) arrayOf<Any?>("com.android.shell", null, userId, 0) else arrayOf<Any?>("com.android.shell", null, userId)
        val clip = method.invoke(service, *args) as? ClipData
        val result = Bundle()
        val value = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
        if (value == null) { result.putBoolean("empty", true); return result }
        // Keep RPC below Binder limits; fail instead of silently truncating content.
        if (value.toByteArray(Charsets.UTF_8).size > 256 * 1024) throw RemoteException("Clipboard exceeds IPC limit")
        result.putString("text", value)
        result.putBoolean("sensitive", clip!!.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) ?: false)
        result.putString("origin", clip.description.extras?.getString("io.github.infinifar.rustclipsync.ORIGIN")?.take(256) ?: "android-local:${clip.description.timestamp}")
        return result
    }

    override fun snapshot(): Bundle {
        requireOwner()
        val token = Binder.clearCallingIdentity()
        try { return readClipboard() }
        catch (_: Exception) { throw RemoteException("Privileged clipboard unavailable") }
        finally { Binder.restoreCallingIdentity(token) }
    }

    @Synchronized override fun observe(value: IClipboardWake?) {
        requireOwner()
        callback = value
        if (started) return
        started = true
        // OS clipboard observation only. A wake carries no body; core owns syncing and retries.
        monitor.scheduleWithFixedDelay({
            val target = callback ?: return@scheduleWithFixedDelay
            try {
                val data = readClipboard()
                val recovered = failed; failed = false
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update((data.getString("text") ?: "").toByteArray(Charsets.UTF_8))
                digest.update(if (data.getBoolean("sensitive")) 1.toByte() else 0.toByte())
                digest.update((data.getString("origin") ?: "").toByteArray(Charsets.UTF_8))
                val next = digest.digest()
                if (recovered || fingerprint?.contentEquals(next) != true) { fingerprint = next; target.changed() }
            } catch (_: Exception) {
                if (!failed) { failed = true; try { target.changed() } catch (_: Exception) { callback = null } }
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    override fun destroy() {
        val caller = Binder.getCallingUid()
        if (caller != ownerUid && caller != 0 && caller != 2000 && caller != Process.myUid()) throw SecurityException("Private clipboard bridge")
        callback = null; fingerprint = null; monitor.shutdownNow()
        kotlin.system.exitProcess(0)
    }
}
