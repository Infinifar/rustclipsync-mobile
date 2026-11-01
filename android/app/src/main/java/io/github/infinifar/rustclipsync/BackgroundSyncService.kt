package io.github.infinifar.rustclipsync

import android.app.Service
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** User-started, bounded dataSync lifetime. Never restarted or started at boot. */
class BackgroundSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session get() = (application as SyncApplication).session
    private var polling: Job? = null
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) session.notifications.mask()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF) // Protected system broadcast only.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            InboxNotifications.START -> {
                try {
                    startForeground(InboxNotifications.SERVICE_ID, InboxNotifications.serviceNotification(this, session.backgroundWritesEnabled), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } catch (_: RuntimeException) { session.serviceStopped(); stopSelf(); return START_NOT_STICKY }
                if (!session.serviceStarted()) { stopSelf(); return START_NOT_STICKY }
                if (polling == null) polling = scope.launch {
                    while (isActive && session.backgroundEnabled && session.connected) {
                        session.pollInbox()
                        // No wake lock, clipboard watcher, or platform-side upload/retry algorithm.
                        delay(30_000)
                    }
                    stopSelf()
                }
            }
            InboxNotifications.STOP -> { session.setBackgroundEnabled(false); stopSelf() }
            InboxNotifications.SHOW, InboxNotifications.HIDE, InboxNotifications.DISMISS -> {
                if (session.backgroundRunning) scope.launch { session.notificationAction(intent) }
                else stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        session.serviceTimedOut()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        session.setBackgroundEnabled(false)
        stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        unregisterReceiver(screenOff)
        session.serviceStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
