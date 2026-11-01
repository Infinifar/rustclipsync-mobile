package io.github.infinifar.rustclipsync

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Plaintext is read only for an explicit, unlocked Show action; it is never placed in an Intent. */
internal class InboxNotifications(
    private val context: Context,
    private val authorized: () -> Boolean = { InboxNotifications.authorized(context) },
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var item: String? = null
    private var ticket: String? = null
    private var body: String? = null
    private var version = 0L
    private val pending = mutableSetOf<PendingIntent>()

    init { channels(context) }

    fun offer(id: String) {
        clear()
        if (!authorized()) return
        item = id
        ticket = UUID.randomUUID().toString()
        publish()
    }

    fun clear() {
        ++version
        body = null; item = null; ticket = null
        pending.forEach { it.cancel() }; pending.clear()
        manager.cancel(INBOX_ID)
    }

    fun mask() {
        ++version
        body = null
        if (item != null) publish()
    }

    fun refreshAuthorization() { if (!authorized()) clear() }

    suspend fun action(intent: Intent, eligible: () -> Boolean, read: suspend (String) -> String?) {
        val id = matchingItem(intent) ?: return
        if (!eligible() || !authorized()) { clear(); return }
        if (intent.action == DISMISS) { clear(); return }
        if (intent.action == HIDE) { mask(); return }
        if (intent.action != SHOW || locked()) return
        val revision = ++version
        val text = try { read(id) } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
        if (revision != version) return
        if (!eligible() || !authorized() || locked() || text == null) { mask(); return }
        // Bound notification IPC size. The Copy action still copies the complete inbox item.
        body = text.take(2_000)
        publish()
    }

    fun claimCopy(intent: Intent): String? =
        if (intent.action == COPY && authorized() && !locked()) matchingItem(intent) else null

    private fun matchingItem(intent: Intent): String? {
        val id = item ?: return null
        // Only scalar strings are accepted; no caller-provided Intent is forwarded.
        return try {
            id.takeIf { intent.getStringExtra(ITEM) == id && intent.getStringExtra(TICKET) == ticket }
        } catch (_: RuntimeException) { null }
    }

    private fun locked() = context.getSystemService(KeyguardManager::class.java).isDeviceLocked

    @SuppressLint("MissingPermission") // Checked on every publication, including action completion.
    private fun publish() {
        val id = item ?: return
        val nonce = ticket ?: return
        if (!authorized()) { clear(); return }
        if (locked()) body = null
        val text = body ?: "**"
        val toggle = if (body == null) SHOW else HIDE
        val public = Notification.Builder(context, INBOX_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle("加密收件箱").setContentText("**").build()
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS)
        val notification = Notification.Builder(context, INBOX_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle("加密收件箱")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setLocalOnly(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setDeleteIntent(track(serviceIntent(context, DISMISS, id, nonce)))
            .addAction(Notification.Action.Builder(null, if (body == null) "显示" else "隐藏",
                track(serviceIntent(context, toggle, id, nonce))).setAuthenticationRequired(true).build())
            .addAction(Notification.Action.Builder(null, "复制", track(PendingIntent.getActivity(context, 0,
                itemIntent(context, InboxCopyActivity::class.java, COPY, id, nonce).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS)))
                .setAuthenticationRequired(true).build())
            .build()
        try { manager.notify(INBOX_ID, notification) }
        catch (_: SecurityException) { clear() }
    }

    private fun track(intent: PendingIntent): PendingIntent = intent.also { pending.add(it) }

    companion object {
        const val SERVICE_ID = 100
        const val INBOX_ID = 101
        const val SERVICE_CHANNEL = "background-sync"
        const val INBOX_CHANNEL = "encrypted-inbox"
        const val START = "io.github.infinifar.rustclipsync.LISTEN"
        const val STOP = "io.github.infinifar.rustclipsync.STOP"
        const val SHOW = "io.github.infinifar.rustclipsync.SHOW"
        const val HIDE = "io.github.infinifar.rustclipsync.HIDE"
        const val DISMISS = "io.github.infinifar.rustclipsync.DISMISS"
        const val COPY = "io.github.infinifar.rustclipsync.COPY"
        private const val ITEM = "item"
        private const val TICKET = "ticket"
        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        fun channels(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
                NotificationChannel(SERVICE_CHANNEL, "后台监听", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(INBOX_CHANNEL, "加密收件箱", NotificationManager.IMPORTANCE_LOW)
                    .apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE },
            ))
        }

        fun authorized(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                && manager.areNotificationsEnabled()
                && manager.getNotificationChannel(INBOX_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        fun serviceNotification(context: Context, write: Boolean): Notification {
            channels(context)
            return Notification.Builder(context, SERVICE_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher).setContentTitle("RustClipSync 后台监听")
                .setContentText(if (write) "后台接收与写入已启用；敏感内容遵循所选策略" else "后台接收加密收件箱；不读写系统剪贴板")
                .setOngoing(true).setOnlyAlertOnce(true).setLocalOnly(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS))
                .addAction(Notification.Action.Builder(null, "停止监听", serviceIntent(context, STOP)).build())
                .build()
        }

        private fun itemIntent(context: Context, target: Class<*>, action: String, id: String, ticket: String) =
            Intent(context, target).setAction(action)
                .setData(Uri.Builder().scheme("rustclipsync").authority("inbox").appendPath(ticket).build())
                .putExtra(ITEM, id).putExtra(TICKET, ticket)

        private fun serviceIntent(context: Context, action: String, id: String = "", ticket: String = "") =
            PendingIntent.getService(context, 0, itemIntent(context, BackgroundSyncService::class.java, action, id, ticket), FLAGS)
    }
}
