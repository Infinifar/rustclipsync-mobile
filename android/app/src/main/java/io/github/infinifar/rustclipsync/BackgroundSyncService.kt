package io.github.infinifar.rustclipsync

import android.app.Service
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** User-started, bounded dataSync lifetime. Never restarted or started at boot. */
class BackgroundSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session get() = (application as SyncApplication).session
    private var polling: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val privacyChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> session.notifications.mask()
                NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED,
                NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED -> {
                    session.notifications.clear()
                    session.refreshNotificationAuthorization()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
        } // Protected system broadcasts only; no caller-supplied extras are used.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(privacyChanges, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(privacyChanges, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            InboxNotifications.START -> {
                try {
                    startForeground(InboxNotifications.SERVICE_ID, InboxNotifications.serviceNotification(this, session.direction, session.paused), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } catch (_: RuntimeException) { session.serviceStopped(); stopSelf(); return START_NOT_STICKY }
                if (!session.serviceStarted()) { stopSelf(); return START_NOT_STICKY }
                session.backgroundWake = { wake.trySend(Unit); Unit }
                wake.trySend(Unit)
                if (polling == null) polling = scope.launch {
                    while (isActive && session.backgroundEnabled && session.connected) {
                        session.pollBackground()
                        // Clipboard wake signals are coalesced; core owns send/receive transitions.
                        withTimeoutOrNull(3_000) { wake.receive() }
                    }
                    stopSelf()
                }
            }
            InboxNotifications.STOP -> { session.configureBackgroundRunning(false); stopSelf() }
            InboxNotifications.SHOW, InboxNotifications.HIDE, InboxNotifications.DISMISS -> {
                if (session.backgroundRunning) scope.launch { session.notificationAction(intent) }
                else stopSelf()
            }
            InboxNotifications.COPY -> {
                if (session.backgroundRunning) scope.launch { session.notificationCopy(intent) }
                else stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        session.notifications.mask()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        session.serviceTimedOut()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing the UI task is not the notification's explicit Stop action.
        if (!SyncSchedule.keepServiceOnTaskRemoval(session.backgroundEnabled, session.connected)) stopSelf()
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        session.backgroundWake = null
        wake.close(); scope.cancel()
        unregisterReceiver(privacyChanges)
        session.serviceStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
