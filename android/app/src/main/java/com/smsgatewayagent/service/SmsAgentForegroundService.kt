package com.smsgatewayagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.smsgatewayagent.MainActivity
import com.smsgatewayagent.R
import com.smsgatewayagent.data.Repository
import com.smsgatewayagent.queue.QueueScheduler

/**
 * Optional "persistent mode" service. When enabled by the user it keeps the agent process
 * resident and visibly running (required by Android for any background long-running work), which
 * maximises SMS-capture reliability on aggressive OEM battery managers.
 */
class SmsAgentForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        QueueScheduler.scheduleNow(applicationContext)
        // Restart if the OS kills us so capture/forwarding stays alive.
        return START_STICKY
    }

    private fun startAsForeground() {
        val channelId = ensureChannel()
        // Tapping the notification opens the app — keeps the persistent notification honest and
        // tappable, which is both better UX and friendlier to Play Protect heuristics.
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("SMS Gateway Agent")
            .setContentText("Active — monitoring incoming SMS and forwarding to your server")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // Must never crash the app. `dataSync` carries a per-day runtime cap on Android 15+ that
        // throws ForegroundServiceStartNotAllowedException ("time limit already exhausted") once
        // hit; a background start can also be disallowed. We use `specialUse` (no daily cap — the
        // honest type for a persistent monitoring agent) and still guard the call: if the platform
        // refuses, we log and stop quietly rather than take down the process.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            runCatching {
                Repository.get(applicationContext)
                    .log("system", "warn", "Persistent service could not start foreground: ${t.message}")
            }
            stopSelf()
        }
    }

    private fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "SMS Agent", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Keeps the SMS gateway agent running"
                    }
                )
            }
        }
        return CHANNEL_ID
    }

    companion object {
        private const val CHANNEL_ID = "sms_agent_service"
        private const val NOTIFICATION_ID = 4711
    }
}
