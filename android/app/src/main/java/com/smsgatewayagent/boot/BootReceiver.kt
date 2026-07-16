package com.smsgatewayagent.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.smsgatewayagent.data.Repository
import com.smsgatewayagent.queue.QueueScheduler
import com.smsgatewayagent.service.SmsAgentForegroundService
import com.smsgatewayagent.sim.SimConfigManager
import com.smsgatewayagent.sim.SimManager

/**
 * Restores the agent after a reboot or app update: reschedules the queue drain (so any messages
 * persisted before shutdown are delivered), reconciles SIM state and restarts the optional
 * persistent foreground service if the user enabled it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val repo = Repository.get(appContext)
                QueueScheduler.schedulePeriodic(appContext)
                QueueScheduler.scheduleNow(appContext)
                SimManager.detectChanges(appContext)
                // A reboot can renumber subscriptions, so reconcile each slot's saved identity
                // against the SIMs now present before re-asserting this station's profile to the
                // server. Both are no-ops if the station was never configured.
                SimConfigManager.reconcileWithCurrentSims(appContext)
                SimConfigManager.resyncIfConfigured(appContext)
                repo.log("system", "info", "Boot/update recovery completed (${intent?.action})")
                if (repo.getSetting("foreground_enabled", "false") == "true") {
                    ContextCompat.startForegroundService(
                        appContext, Intent(appContext, SmsAgentForegroundService::class.java)
                    )
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
