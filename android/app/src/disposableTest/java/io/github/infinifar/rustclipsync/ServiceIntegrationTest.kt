package io.github.infinifar.rustclipsync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.infinifar.rustclipsync.bindings.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Disposable APK only: real HTTPS/core, synthetic clipboard and isolated RCS1 files. */
@RunWith(AndroidJUnit4::class)
class ServiceIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.also {
        check(it.packageName.endsWith(".disposable"))
        check(InstrumentationRegistry.getArguments().getString("disposableFixture") == "1")
    }
    private val root = File(context.noBackupFilesDir, "disposable-service-e2e")
    private val server = InstrumentationRegistry.getArguments().getString("server") ?: error("fixture URL required")
    private class FilePlatform(directory: File) : Platform {
        val store = SecureState(directory)
        @Volatile var selection: ClipboardText? = null
        val writes = AtomicInteger()
        override fun clipboardSnapshot() = selection
        override fun clipboardWrite(value: ClipboardText) { selection = value; writes.incrementAndGet() }
        override fun loadState() = store.load()
        override fun saveState(ciphertext: ByteArray) = store.save(ciphertext)
    }
    private fun platform(name: String) = FilePlatform(File(root, name))
    private fun config(policy: SensitivePolicy) = MobileConfig(server, "synthetic-mobile", "synthetic-mobile-password",
        ByteArray(32) { 0x42 }, policy)
    private fun open(adapter: FilePlatform, policy: SensitivePolicy = SensitivePolicy.ENCRYPTED_INBOX) =
        MobileSession.forDisposableTest(config(policy), adapter)
    private fun copy(value: String, sensitive: Boolean = false) = ClipboardText(value, sensitive, null)
    private suspend fun close(vararg sessions: MobileSession) {
        sessions.forEach { it.requestClose() }
        sessions.forEach { try { it.shutdown() } finally { it.destroy() } }
    }
    private fun assertEncrypted(adapter: FilePlatform, plaintext: String) {
        val bytes = adapter.store.load() ?: error("missing synthetic state")
        assertArrayEquals("RCS1".toByteArray(), bytes.copyOfRange(0, 4))
        assertFalse("plaintext must not occur in RCS1 file", bytes.toString(Charsets.UTF_8).contains(plaintext))
    }

    @Test fun sendReceiveAndSensitiveInbox() = runBlocking {
        // The ordinary constructor must reject the fixture's untrusted certificate.
        val strict = MobileSession(config(SensitivePolicy.DISABLED), platform("strict-tls"))
        try {
            try { strict.check(); fail("ordinary constructor trusted the fixture certificate") }
            catch (_: MobileException.Network) { }
            assertFalse(strict.status().paired)
        } finally { close(strict) }
        val a = platform("a"); val b = platform("b")
        val sender = open(a); val receiver = open(b, SensitivePolicy.DISABLED)
        try {
            sender.check(); receiver.check()
            assertEquals(0uL, sender.status().cursor)
            a.selection = copy(PLAIN)
            assertEquals(SyncOutcome.UPLOADED, sender.sendClipboard()); sender.retryPending()
            assertEquals(SyncOutcome.DOWNLOADED, receiver.receive()); receiver.retryPending()
            assertEquals(PLAIN, b.selection?.text)
            assertEquals(1, b.writes.get())
            a.selection = copy(SKIPPED, true)
            assertEquals(SyncOutcome.UPLOADED, sender.sendClipboard()); sender.retryPending()
            assertEquals(SyncOutcome.FILTERED, receiver.receive()); receiver.retryPending()
            assertNull(receiver.status().inbox)
            assertEquals(PLAIN, b.selection?.text)
            assertEquals(1, b.writes.get())
            receiver.updatePolicy(SensitivePolicy.ENCRYPTED_INBOX)
            a.selection = copy(PRIVATE, true)
            assertEquals(SyncOutcome.UPLOADED, sender.sendClipboard()); sender.retryPending()
            assertEquals(SyncOutcome.STORED, receiver.receive()); receiver.retryPending()
            val inbox = receiver.status().inbox ?: error("missing private inbox")
            assertEquals(PRIVATE, receiver.readInbox(inbox.id))
            assertEquals(3uL, receiver.status().cursor)
            assertEquals(1, b.writes.get())
            assertEncrypted(b, PRIVATE)
        } finally { close(sender, receiver) }
    }

    @Test fun persistOfflineQueueUntilKilled() = runBlocking {
        val a = platform("a"); val session = open(a)
        try {
            assertTrue(session.status().paired)
            for (value in listOf(OFFLINE_FIRST, OFFLINE_LATEST)) {
                a.selection = copy(value)
                try { session.sendClipboard(); fail("disconnected endpoint unexpectedly accepted upload") }
                catch (_: MobileException.Network) { }
            }
            assertTrue(session.status().hasPending)
            assertEncrypted(a, OFFLINE_LATEST)
            File(root, "offline-ready").writeText("ready")
            // Instrumentation thread only. The host deliberately force-stops this live process.
            CountDownLatch(1).await(90, TimeUnit.SECONDS)
            fail("host did not stop the test process")
        } finally { close(session) }
    }

    @Test fun recoverAfterProcessAndDeviceRestart() = runBlocking {
        val a = platform("a"); val b = platform("b")
        a.selection = copy(OFFLINE_LATEST)
        val sender = open(a); val receiver = open(b)
        try {
            assertTrue(sender.status().paired)
            assertTrue(sender.status().hasPending)
            val inbox = receiver.status().inbox ?: error("inbox did not survive device reboot")
            assertEquals(PRIVATE, receiver.readInbox(inbox.id))
            assertEquals(0, b.writes.get())
            assertEquals(SyncOutcome.CURRENT, receiver.receive()); receiver.retryPending()
            assertEquals(0, b.writes.get())
            receiver.copyInbox(inbox.id)
            assertEquals(PRIVATE, b.selection?.text)
            assertTrue(b.selection?.sensitive == true)
            assertEquals(1, b.writes.get())
            receiver.clearInbox(inbox.id)
            assertNull(receiver.status().inbox)
            // Core first cancels the uncertain first copy, then flushes the saved latest copy.
            assertEquals(SyncOutcome.CURRENT, sender.retryPending())
            assertTrue(sender.status().hasPending)
            assertEquals(SyncOutcome.UPLOADED, sender.retryPending()); sender.retryPending()
            assertEquals(4uL, sender.status().cursor)
            assertEquals(SyncOutcome.DOWNLOADED, receiver.receive()); receiver.retryPending()
            assertEquals(OFFLINE_LATEST, b.selection?.text)
            assertEquals(4uL, receiver.status().cursor)
            receiver.updatePolicy(SensitivePolicy.AUTO_WRITE)
            a.selection = copy(AUTOMATIC, true)
            assertEquals(SyncOutcome.UPLOADED, sender.sendClipboard()); sender.retryPending()
            assertEquals(SyncOutcome.DOWNLOADED, receiver.receive()); receiver.retryPending()
            assertEquals(AUTOMATIC, b.selection?.text)
            assertTrue(b.selection?.sensitive == true)
            receiver.updatePolicy(SensitivePolicy.DISABLED)
            a.selection = copy("123456")
            assertEquals(SyncOutcome.UPLOADED, sender.sendClipboard()); sender.retryPending()
            assertEquals(SyncOutcome.DOWNLOADED, receiver.receive()); receiver.retryPending()
            assertEquals("123456", b.selection?.text)
            assertFalse(b.selection?.sensitive ?: true)
            assertEquals(6uL, receiver.status().cursor)
            assertEquals(4, b.writes.get())
            assertFalse(sender.status().hasPending)
        } finally { close(sender, receiver) }
    }

    @Test fun persistUncertainUploadUntilKilled() = runBlocking {
        val a = platform("a"); a.selection = copy(UNCERTAIN)
        val session = open(a)
        try {
            session.check()
            try { session.sendClipboard(); fail("lost response unexpectedly reached caller") }
            catch (_: MobileException.Network) { }
            assertTrue(session.status().hasPending)
            assertEquals(6uL, session.status().cursor)
            File(root, "uncertain-ready").writeText("ready")
            CountDownLatch(1).await(90, TimeUnit.SECONDS)
            fail("host did not stop the test process")
        } finally { close(session) }
    }

    @Test fun recoverCommittedUploadAfterServerRestart() = runBlocking {
        val a = platform("a"); val b = platform("b")
        a.selection = copy(UNCERTAIN)
        val sender = open(a); val receiver = open(b)
        try {
            assertTrue(sender.status().hasPending)
            assertEquals(SyncOutcome.UPLOADED, sender.retryPending()); sender.retryPending()
            assertEquals(7uL, sender.status().cursor)
            assertFalse(sender.status().hasPending)
            assertEquals(SyncOutcome.DOWNLOADED, receiver.receive()); receiver.retryPending()
            assertEquals(UNCERTAIN, b.selection?.text)
            assertEquals(7uL, receiver.status().cursor)
            assertEquals(1, b.writes.get())
            assertEquals(SyncOutcome.CURRENT, receiver.receive())
            assertEquals(1, b.writes.get())
        } finally { close(sender, receiver) }
    }

    companion object {
        private const val PLAIN = "synthetic hello 中文 😀"
        private const val SKIPPED = "synthetic skipped sensitive"
        private const val PRIVATE = "synthetic encrypted private inbox"
        private const val OFFLINE_FIRST = "synthetic offline first"
        private const val OFFLINE_LATEST = "synthetic offline latest"
        private const val AUTOMATIC = "synthetic explicit automatic write"
        private const val UNCERTAIN = "synthetic committed response lost"
    }
}
