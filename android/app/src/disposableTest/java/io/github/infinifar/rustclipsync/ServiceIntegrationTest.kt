package io.github.infinifar.rustclipsync

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    private class FilePlatform(directory: File) : SessionPlatform {
        val store = SecureState(directory)
        @Volatile var selection: ClipboardText? = null
        val writes = AtomicInteger()
        val reads = AtomicInteger()
        @Volatile var focused = true
        @Volatile var backgroundWriteAllowed = false
        override fun observe(session: MobileSession) { }
        override fun focus(value: Boolean) { focused = value }
        private val writeGate = MainThreadGate()
        private var copyId: String? = null
        private var copyPermitted: (() -> Boolean)? = null
        override fun backgroundWrite(value: Boolean) { backgroundWriteAllowed = value }
        override fun explicitCopy(id: String?, permitted: () -> Boolean) { copyId = id; copyPermitted = if (id == null) null else permitted }
        override fun revoke() { writeGate.revoke(); focused = false; backgroundWriteAllowed = false; copyId = null; copyPermitted = null }
        override fun clipboardSnapshot(): ClipboardText? {
            check(focused) { "background clipboard read attempted" }
            reads.incrementAndGet()
            return selection
        }
        override fun clipboardWrite(value: ClipboardText) = writeGate.call {
            check(if (copyId != null) value.origin == copyId && value.sensitive && copyPermitted?.invoke() == true
                else focused || backgroundWriteAllowed) { "unauthorized background write attempted" }
            selection = value; writes.incrementAndGet(); Unit
        }
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

    private suspend fun eventually(stage: String, predicate: suspend () -> Boolean) {
        try { withTimeout(45_000) { while (!predicate()) delay(100) } }
        catch (error: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("Timed out at " + stage, error) }
    }
    private suspend fun <T> main(operation: () -> T): T = withContext(Dispatchers.Main) { operation() }

    @Test fun backgroundNotificationsAndOptionalWrites() = runBlocking {
        val app = context.applicationContext as SyncApplication
        val a = platform("background-sender"); val b = platform("background-receiver")
        val sender = open(a)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val previous = main { app.session }
        val controller = main {
            previous.close()
            context.getSharedPreferences("background-running-v2", 0).edit().clear().commit()
            SessionController(context, { b to ByteArray(32) { 0x42 } }, MobileSession::forDisposableTest)
                .also { app.session = it }
        }
        fun inbox() = notifications.activeNotifications.firstOrNull { it.id == InboxNotifications.INBOX_ID }?.notification
        suspend fun publish(text: String, sensitive: Boolean) {
            a.selection = copy(text, sensitive)
            assertEquals(SyncOutcome.UPLOADED, sender.sendClipboard()); sender.retryPending()
        }
        try {
            sender.check()
            main { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
            eventually("activity visible") { main { controller.foreground } }
            // Wait for the actual window focus callback before starting a foreground connection.
            eventually("foreground connection") {
                main { controller.connect(server, "synthetic-mobile", "synthetic-mobile-password", SensitivePolicy.ENCRYPTED_INBOX); controller.connected }
            }
            main { controller.configureBackgroundRunning(true) }
            eventually("foreground service running") { main { controller.backgroundRunning } }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            eventually("activity backgrounded") { main { !controller.foreground } }
            val reads = b.reads.get()
            val first = "synthetic background inbox alpha"
            val second = "synthetic background inbox beta"
            publish(first, true)
            // Exercise the real service's 30-second scheduler for the first receive.
            eventually("scheduled masked inbox") { inbox()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?.let { value -> value.length >= 7 && value.all { it == '*' } } == true }
            assertEquals(reads, b.reads.get()); assertEquals(0, b.writes.get())
            assertEncrypted(b, first)
            val hidden = inbox() ?: error("missing hidden inbox notification")
            assertEquals(listOf(context.translated(R.string.show), context.translated(R.string.copy)), hidden.actions.map { it.title.toString() })
            assertTrue((hidden.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).let { value -> value.length >= 7 && value.all { it == '*' } })
            assertTrue(hidden.actions.all { it.actionIntent.isImmutable })
            val staleShow = hidden.actions[0].actionIntent
            staleShow.send()
            eventually("explicit show") { inbox()?.actions?.get(0)?.title?.toString() == context.translated(R.string.hide) }
            assertEquals(first, inbox()!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            inbox()!!.actions[0].actionIntent.send()
            eventually("explicit hide") { inbox()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?.let { value -> value.length >= 7 && value.all { it == '*' } } == true }
            publish(second, true)
            withContext(Dispatchers.Main) { controller.pollBackground() }
            eventually("replacement masked") { inbox()?.actions?.get(0)?.title?.toString() == context.translated(R.string.show) }
            try { staleShow.send() } catch (_: android.app.PendingIntent.CanceledException) { }; delay(300)
            assertTrue((inbox()!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).let { value -> value.length >= 7 && value.all { it == '*' } })
            inbox()!!.actions[0].actionIntent.send()
            eventually("replacement reveal") { inbox()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == second }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            eventually("screen-off remasks") { inbox()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?.let { value -> value.length >= 7 && value.all { it == '*' } } == true }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
            eventually("device unlocked for copy") {
                !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked && context.getSystemService(android.os.PowerManager::class.java).isInteractive
            }
            inbox()!!.actions[1].actionIntent.send()
            eventually("notification explicit copy") { b.writes.get() == 1 }
            assertEquals(second, b.selection?.text); assertTrue(b.selection?.sensitive == true)
            assertTrue(main { !controller.foreground && controller.policySelection == SensitivePolicy.ENCRYPTED_INBOX })
            assertTrue(b.backgroundWriteAllowed)
            eventually("copy remasks") { inbox()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?.let { value -> value.length >= 7 && value.all { it == '*' } } == true }
            assertEquals(reads, b.reads.get())

            publish("synthetic ordinary background write", false)
            withContext(Dispatchers.Main) { controller.pollBackground() }
            eventually("opt-in ordinary write") { b.writes.get() == 2 }
            assertEquals("synthetic ordinary background write", b.selection?.text)
            assertEquals(reads, b.reads.get())
            assertNotNull(inbox()) // Returning to the app masks the saved inbox; it remains actionable.
            assertTrue((inbox()!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).let { value -> value.length >= 7 && value.all { it == '*' } })

            // Encrypted-inbox policy still protects marked data while ordinary receiving writes.
            publish("synthetic marked inbox with writes enabled", true)
            withContext(Dispatchers.Main) { controller.pollBackground() }
            eventually("marked still in inbox") { inbox() != null }
            assertEquals(2, b.writes.get())
            val obsolete = inbox()!!.actions[0].actionIntent
            main { controller.configureBackgroundRunning(false) }
            eventually("stop revokes session") { inbox() == null && main { !controller.backgroundRunning && !controller.connected } }
            try { obsolete.send() } catch (_: android.app.PendingIntent.CanceledException) { }; delay(300)
            assertNull(inbox())
        } finally {
            main { controller.configureBackgroundRunning(false); controller.close() }
            delay(300)
            main { app.session = previous }
            close(sender)
        }
    }

    @Test fun restoredInboxActionsInNotificationShade() = runBlocking {
        val text = "Synthetic encrypted inbox UI proof"
        val a = platform("shade-sender"); val b = platform("shade-receiver")
        val sender = open(a); val seed = open(b)
        try {
            sender.check(); seed.check()
            a.selection = copy(text, true)
            assertEquals(SyncOutcome.UPLOADED, sender.sendClipboard()); sender.retryPending()
            assertEquals(SyncOutcome.STORED, seed.receive()); seed.retryPending()
            assertEncrypted(b, text)
        } finally { close(sender, seed) }
        // Reopen saved inbox state. No new message is sent after this connection.
        val app = context.applicationContext as SyncApplication
        val previous = main { app.session }
        val controller = main {
            previous.close()
            context.getSharedPreferences("background-running-v2", 0).edit().clear().commit()
            SessionController(context, { b to ByteArray(32) { 0x42 } }, MobileSession::forDisposableTest)
                .also { app.session = it }
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        fun inbox() = manager.activeNotifications.firstOrNull { it.id == InboxNotifications.INBOX_ID }?.notification
        suspend fun shade(stage: String, predicate: suspend () -> Boolean) {
            try { withTimeout(120_000) { while (!predicate()) delay(100) } }
            catch (error: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("SystemUI stage: " + stage, error) }
        }
        try {
            main { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
            eventually("shade activity visible") { main { controller.foreground } }
            eventually("shade foreground connection") {
                main { controller.connect(server, "synthetic-mobile", "synthetic-mobile-password", SensitivePolicy.ENCRYPTED_INBOX); controller.connected }
            }
            assertNotNull(main { controller.status?.inbox })
            main { controller.configureBackgroundRunning(true) }
            eventually("saved inbox restored to notification") { inbox()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?.let { value -> value.length >= 7 && value.all { it == '*' } } == true }
            assertTrue(main { controller.foreground })
            File(root, "shade-ready").writeText("ready")
            // Host must click the actual SystemUI buttons; this method never sends a PendingIntent itself.
            shade("show clicked") { inbox()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == text }
            File(root, "shade-shown").writeText("ready")
            shade("hide clicked") { inbox()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?.let { value -> value.length >= 7 && value.all { it == '*' } } == true }
            File(root, "shade-hidden").writeText("ready")
            shade("copy clicked") { b.writes.get() == 1 }
            assertEquals(text, b.selection?.text); assertTrue(b.selection?.sensitive == true)
            assertTrue(main { controller.policySelection == SensitivePolicy.ENCRYPTED_INBOX })
            assertEquals(listOf(context.translated(R.string.show), context.translated(R.string.copy)), inbox()!!.actions.map { it.title.toString() })
        } finally {
            main { controller.configureBackgroundRunning(false); controller.close() }
            delay(300)
            main { app.session = previous }
        }
    }

    @Test fun foregroundServiceTimeoutStopsAndRevokes() = runBlocking {
        check(Build.VERSION.SDK_INT >= 35)
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        val oldTimeout = shell("device_config get activity_manager data_sync_fgs_timeout_duration")
        val app = context.applicationContext as SyncApplication
        val adapter = platform("timeout-receiver")
        val previous = main { app.session }
        val controller = main {
            previous.close()
            context.getSharedPreferences("background-running-v2", 0).edit().clear().commit()
            SessionController(context, { adapter to ByteArray(32) { 0x42 } }, MobileSession::forDisposableTest)
                .also { app.session = it }
        }
        try {
            assertEquals("", shell("device_config put activity_manager data_sync_fgs_timeout_duration 2500"))
            assertEquals("2500", shell("device_config get activity_manager data_sync_fgs_timeout_duration"))
            main { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
            eventually("timeout activity visible") { main { controller.foreground } }
            eventually("timeout foreground connection") {
                main { controller.connect(server, "synthetic-mobile", "synthetic-mobile-password", SensitivePolicy.ENCRYPTED_INBOX); controller.connected }
            }
            main { controller.configureBackgroundRunning(true) }
            eventually("timeout service running") { main { controller.backgroundRunning } }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            eventually("system timeout closes session") { main { !controller.backgroundEnabled && !controller.backgroundRunning && !controller.connected } }
            assertFalse(adapter.backgroundWriteAllowed)
            assertEquals(0, adapter.writes.get())
        } finally {
            main { controller.configureBackgroundRunning(false); controller.close() }
            delay(300)
            main { app.session = previous }
            if (oldTimeout == "null") shell("device_config delete activity_manager data_sync_fgs_timeout_duration")
            else { check(oldTimeout.all { it.isDigit() }); shell("device_config put activity_manager data_sync_fgs_timeout_duration " + oldTimeout) }
        }
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
            // A still-marked local selection must not block an ordinary receive
            // after disabling sensitive sync; the digits are not a secret heuristic.
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
