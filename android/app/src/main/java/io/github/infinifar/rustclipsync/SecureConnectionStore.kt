package io.github.infinifar.rustclipsync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** No backup, preferences, saved-state Bundle, or core state file contains these credentials. */
internal class SecureConnectionStore(
    directory: File,
    private val alias: String = "rustclipsync-connection-v1",
) : ConnectionStore {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "connection"))
    private val file = AtomicFile(File(directory, "credentials.enc"))

    init { check(directory.isDirectory || directory.mkdirs()) { "connection storage unavailable" } }

    @Synchronized override fun load(): ConnectionCredentials? {
        val input = try { file.openRead() } catch (error: FileNotFoundException) {
            // Access errors and interrupted files are not a fresh installation.
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() ||
                !file.baseFile.parentFile!!.canRead()) throw error
            return null
        }
        val bytes = input.use {
            val buffer = ByteArray(ConnectionVault.MAX_ENVELOPE + 1)
            var count = 0
            while (count < buffer.size) {
                val read = it.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            buffer.copyOf(count)
        }
        return ConnectionVault.open(bytes, key(create = false))
    }

    @Synchronized override fun save(value: ConnectionCredentials) {
        val bytes = ConnectionVault.seal(value, key(create = true))
        val stream = file.startWrite()
        try {
            Os.fchmod(stream.fd, 384) // 0600 before writing ciphertext
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized override fun clear() {
        file.delete()
        check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists() &&
            !File(file.baseFile.path + ".new").exists()) { "connection deletion failed" }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        // Pairing key, device identity and encrypted inbox are intentionally separate.
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null)
        if (existing != null) return existing as SecretKey
        check(create) { "connection key unavailable" }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }
}
