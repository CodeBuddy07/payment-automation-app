package com.smsgatewayagent.sim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires on SIM state transitions and asks [SimManager] to reconcile against the last snapshot. */
class SimChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val changes = SimManager.detectChanges(appContext)
                // When the SIM lineup actually changed, first reconcile each slot's saved identity
                // (number/carrier/iccid) against the SIM now present — keeping the operator's
                // slot-sticky record flag + service map — then re-push the station profile so the
                // server's round-robin pool always holds the real, current numbers. Both are no-ops
                // if this station was never configured/synced before.
                if (changes.length() > 0) {
                    SimConfigManager.reconcileWithCurrentSims(appContext)
                    SimConfigManager.resyncIfConfigured(appContext)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
