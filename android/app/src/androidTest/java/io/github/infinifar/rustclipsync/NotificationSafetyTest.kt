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

    @Test fun deniedAuthorizationNeverPublishesInbox() {
        val notifications = InboxNotifications(context) { false }
        try { notifications.offer("synthetic-item"); assertNull(inbox()) }
        finally { notifications.clear() }
    }

    @Test fun defaultNotificationIsMaskedPrivateAndUsesImmutableActions() {
        val notifications = InboxNotifications(context)
        try {
            notifications.offer("10000000-0000-4000-8000-000000000001")
            val hidden = inbox() ?: error("fixture must grant notifications")
            assertEquals("**", hidden.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals("**", hidden.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(Notification.VISIBILITY_PRIVATE, hidden.visibility)
            assertEquals(listOf("显示", "复制"), hidden.actions.map { it.title.toString() })
            assertTrue(hidden.actions.all { it.actionIntent.isImmutable && it.actionIntent.creatorPackage == context.packageName })
            notifications.mask()
            assertEquals("**", inbox()!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            notifications.clear()
            assertNull(inbox())
        } finally { notifications.clear() }
    }
}
