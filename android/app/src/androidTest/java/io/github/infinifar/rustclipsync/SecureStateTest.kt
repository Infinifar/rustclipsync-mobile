package io.github.infinifar.rustclipsync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.infinifar.rustclipsync.bindings.AdapterException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Fresh temporary files only; no real state, KeyStore key or clipboard is accessed. */
@RunWith(AndroidJUnit4::class)
class SecureStateTest {
    private fun directory() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "synthetic-${UUID.randomUUID()}")
    @Test fun opaqueCiphertextReplacementUsesPublicAndroidDirectorySyncApi() {
        val directory = directory()
        try {
            val store = SecureState(directory)
            assertNull(store.load())
            val first = byteArrayOf(82, 67, 83, 49, 1, 2, 3)
            val replacement = byteArrayOf(82, 67, 83, 49, 4, 5, 6)
            store.save(first)
            assertArrayEquals(first, store.load())
            store.save(replacement)
            assertArrayEquals(replacement, store.load())
            assertFalse(File(directory, "session.rcs.tmp").exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun oversizedExistingStateIsAnErrorRatherThanAbsentState() {
        val directory = directory()
        try {
            val store = SecureState(directory)
            RandomAccessFile(File(directory, "session.rcs"), "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
            try { store.load(); fail("oversized state was accepted") } catch (_: AdapterException.Storage) { }
            assertTrue(File(directory, "session.rcs").exists())
        } finally { directory.deleteRecursively() }
    }
}
