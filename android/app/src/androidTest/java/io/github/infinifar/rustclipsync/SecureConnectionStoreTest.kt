package io.github.infinifar.rustclipsync

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic credentials, temporary files and a unique test-only Keystore alias. */
@RunWith(AndroidJUnit4::class)
class SecureConnectionStoreTest {
    private fun fixture(test: (File, String) -> Unit) {
        val id = UUID.randomUUID()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "connection-test-$id")
        val alias = "rustclipsync-test-$id"
        try { test(directory, alias) }
        finally {
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test fun newStoreRestoresCredentialsAndForgetKeepsPairingFiles() = fixture { directory, alias ->
        val store = SecureConnectionStore(directory, alias)
        assertNull(store.load())
        val pairing = File(directory, "session.rcs").apply { writeBytes(byteArrayOf(82, 67, 83, 49)) }
        store.save(ConnectionCredentials("https://synthetic.invalid", "synthetic", "synthetic-secret"))
        val file = File(directory, "credentials.enc")
        assertEquals(384, Os.stat(file.path).st_mode and 511)
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("synthetic-secret"))
        val reopened = SecureConnectionStore(directory, alias).load()!!
        assertEquals("https://synthetic.invalid", reopened.server)
        assertEquals("synthetic", reopened.username)
        assertEquals("synthetic-secret", reopened.password)
        store.save(ConnectionCredentials("https://synthetic.invalid", "synthetic", "replacement"))
        assertEquals("replacement", SecureConnectionStore(directory, alias).load()!!.password)
        store.clear()
        assertNull(SecureConnectionStore(directory, alias).load())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
        assertArrayEquals(byteArrayOf(82, 67, 83, 49), pairing.readBytes())
    }

    @Test fun corruptionAndMissingKeyDoNotEraseOrRegenerateCredentials() = fixture { directory, alias ->
        val store = SecureConnectionStore(directory, alias)
        store.save(ConnectionCredentials("https://synthetic.invalid", "synthetic", "synthetic-secret"))
        val file = File(directory, "credentials.enc")
        val original = file.readBytes()
        val damaged = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        assertThrows(Exception::class.java) { SecureConnectionStore(directory, alias).load() }
        assertArrayEquals(damaged, file.readBytes())
        file.writeBytes(original)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertThrows(Exception::class.java) { SecureConnectionStore(directory, alias).load() }
        assertArrayEquals(original, file.readBytes())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
    }
}
