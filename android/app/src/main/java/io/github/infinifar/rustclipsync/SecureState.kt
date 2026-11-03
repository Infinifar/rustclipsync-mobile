package io.github.infinifar.rustclipsync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import io.github.infinifar.rustclipsync.bindings.AdapterException
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only exact RCS1 ciphertext goes in the state file; key wrapping is a platform concern. */
internal class SecureState(private val directory: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "sync"))
    init {
        if (!directory.isDirectory && !directory.mkdirs()) throw AdapterException.Storage()
    }
    private val state = File(directory, "session.rcs")
    private val wrappedKey = File(directory, "state-key.wrap")

    fun load(): ByteArray? = guarded {
        if (!exists(state)) null else {
            if (state.length() > MAX_STATE) throw AdapterException.Storage()
            state.inputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_STATE + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size() > MAX_STATE) throw AdapterException.Storage()
                }
                output.toByteArray()
            }
        }
    }
    fun save(ciphertext: ByteArray) = guarded {
        if (ciphertext.size > MAX_STATE) throw AdapterException.Storage()
        durableReplace(state, ciphertext)
    }
    fun key(): ByteArray = guarded {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (exists(wrappedKey)) {
            val wrapping = store.getKey(ALIAS, null) as? SecretKey ?: throw AdapterException.Storage()
            if (wrappedKey.length() > 256) throw AdapterException.Storage()
            val value = wrappedKey.readBytes()
            if (value.size < 29) throw AdapterException.Storage()
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(128, value.copyOfRange(0, 12)))
                doFinal(value.copyOfRange(12, value.size))
            }.also { if (it.size != 32) throw AdapterException.Storage() }
        } else {
            // Missing key with existing state is an error, never a new installation.
            if (exists(state)) throw AdapterException.Storage()
            val wrapping = (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
                generateKey()
            }
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapping) }
            durableReplace(wrappedKey, cipher.iv + cipher.doFinal(raw))
            raw
        }
    }
    private fun exists(file: File): Boolean = try {
        Os.stat(file.path); true
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw AdapterException.Storage()
    }
    private fun durableReplace(file: File, bytes: ByteArray) {
        val temporary = File(directory, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                Os.chmod(temporary.path, 384) // 0600
                output.write(bytes)
                output.fd.sync()
            }
            Os.rename(temporary.path, file.path)
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw AdapterException.Storage()
                Os.fsync(fd)
            } finally { Os.close(fd) }
        } finally { temporary.delete() }
    }
    private inline fun <T> guarded(operation: () -> T): T = try { operation() } catch (_: Exception) { throw AdapterException.Storage() }
    companion object {
        private const val ALIAS = "rustclipsync-state-wrap-v1"
        private const val MAX_STATE = 64 * 1024 * 1024
    }
}
