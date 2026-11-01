package io.github.infinifar.rustclipsync

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationSafetyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private fun inbox() = manager.activeNotifications.firstOrNull { it.id == InboxNotifications.INBOX_ID }?.notification

    // NotificationManager enqueues publication/cancellation; its binder return is not completion.
    private fun awaitInbox(present: Boolean): Notification? {
        repeat(100) {
            val value = inbox()
            if ((value != null) == present) return value
            Thread.sleep(20) // Instrumentation thread only.
        }
        error("notification manager did not settle; expected presence=" + present)
    }

    @Test fun deniedAuthorizationNeverPublishesInbox() {
        val notifications = InboxNotifications(context) { false }
        try { notifications.offer("synthetic-item"); assertNull(awaitInbox(false)) }
        finally { notifications.clear() }
    }

    @Test fun lostAuthorizationRemovesTheExistingNotification() {
        var allowed = true
        val notifications = InboxNotifications(context) { allowed }
        try {
            notifications.offer("20000000-0000-4000-8000-000000000001")
            assertNotNull(awaitInbox(true))
            allowed = false
            notifications.refreshAuthorization()
            assertNull(awaitInbox(false))
        } finally { notifications.clear() }
    }

    @Test fun syncRestoresSavedInboxWithoutResettingTheCurrentTicket() {
        val notifications = InboxNotifications(context)
        try {
            val id = "30000000-0000-4000-8000-000000000001"
            notifications.sync(id, true)
            val first = awaitInbox(true)!!.actions[0].actionIntent
            notifications.sync(id, true)
            assertEquals(first, awaitInbox(true)!!.actions[0].actionIntent)
            notifications.clear()
            assertNull(awaitInbox(false))
            notifications.sync(id, true)
            val restored = awaitInbox(true)!!.actions[0].actionIntent
            assertNotEquals(first, restored)
            notifications.sync(id, false)
            assertNull(awaitInbox(false))
        } finally { notifications.clear() }
    }

    @Test fun defaultNotificationIsMaskedPrivateAndUsesImmutableActions() {
        val notifications = InboxNotifications(context)
        try {
            notifications.offer("10000000-0000-4000-8000-000000000001")
            val hidden = awaitInbox(true) ?: error("fixture must grant notifications")
            assertEquals("**", hidden.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals("**", hidden.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(Notification.VISIBILITY_PRIVATE, hidden.visibility)
            assertEquals(listOf("显示", "复制"), hidden.actions.map { it.title.toString() })
            assertFalse(hidden.actions[0].isAuthenticationRequired)
            assertTrue(hidden.actions[1].isAuthenticationRequired)
            assertTrue(hidden.actions.all { it.actionIntent.isImmutable && it.actionIntent.creatorPackage == context.packageName })
            notifications.mask()
            assertEquals("**", inbox()!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            notifications.clear()
            assertNull(awaitInbox(false))
        } finally { notifications.clear() }
    }
}
