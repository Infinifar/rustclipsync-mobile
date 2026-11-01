package io.github.infinifar.rustclipsync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.infinifar.rustclipsync.bindings.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises generated Kotlin -> Rust -> Kotlin without OS clipboard, accounts or network. */
@RunWith(AndroidJUnit4::class)
class BindingSmokeTest {
    private class MemoryPlatform : Platform {
        @Volatile var ciphertext: ByteArray? = null
        override fun loadState() = ciphertext
        override fun saveState(ciphertext: ByteArray) { this.ciphertext = ciphertext }
        override fun clipboardSnapshot(): ClipboardText? = error("status must not read clipboard")
        override fun clipboardWrite(value: ClipboardText) = error("status must not write clipboard")
    }
    @Test fun encryptedStateAndRepeatedShutdown() = runBlocking {
        val adapter = MemoryPlatform()
        val session = MobileSession(MobileConfig("https://synthetic.invalid/", "synthetic", "synthetic", ByteArray(32) { 7 }, SensitivePolicy.DISABLED), adapter)
        try {
            assertFalse(session.status().paired)
            assertArrayEquals("RCS1".toByteArray(), adapter.ciphertext!!.copyOfRange(0, 4))
            session.requestClose(); session.requestClose()
            session.shutdown(); session.shutdown()
            try { session.status(); fail("closed session accepted status") } catch (_: MobileException.Closed) { }
        } finally { session.shutdown(); session.destroy() }
    }
}
