package com.smsgatewayagent.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import com.smsgatewayagent.bridge.AgentEvents
import com.smsgatewayagent.data.Repository
import com.smsgatewayagent.device.DeviceInfoProvider
import com.smsgatewayagent.model.SmsMessage
import com.smsgatewayagent.pipeline.SmsPipeline
import com.smsgatewayagent.sim.SimManager
import java.security.MessageDigest

/**
 * Receives `SMS_RECEIVED` broadcasts, reconstructs multipart messages and forwards them to the
 * native [SmsPipeline]. Runs off the main thread via [goAsync] so the full pipeline (rule match,
 * processing, enqueue) completes even if the app UI is not running.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // Concatenate multipart bodies; metadata comes from the first part.
        val sender = messages[0].displayOriginatingAddress ?: messages[0].originatingAddress ?: "unknown"
        val timestamp = messages[0].timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val body = buildString { messages.forEach { append(it.displayMessageBody ?: it.messageBody ?: "") } }

        val subscriptionId = intent.getIntExtra(SUB_EXTRA, intent.getIntExtra("subscription", -1))
        val appContext = context.applicationContext

        // The SMS_RECEIVED broadcast reliably carries the subscription id but frequently omits the
        // slot extra (SLOT_INDEX). A raw -1 slot would fail the record gate even for a SIM the
        // operator enabled, so resolve the real slot from the subscription id, falling back to the
        // sole active SIM's slot on a single-SIM phone.
        var slot = intent.getIntExtra(SLOT_EXTRA, intent.getIntExtra("slot", -1))
        if (slot < 0) slot = SimManager.slotForSubscription(appContext, subscriptionId)
        if (slot < 0) slot = SimManager.soleActiveSlot(appContext)

        val message = SmsMessage(
            hash = hash(sender, body, timestamp),
            sender = sender,
            body = body,
            timestamp = timestamp,
            simSlot = slot,
            subscriptionId = subscriptionId,
            deviceId = DeviceInfoProvider.deviceId(appContext),
            phoneNumber = null
        )

        val pending = goAsync()
        // A partial wake lock guarantees the CPU stays up long enough to finish matching,
        // processing and enqueuing even if the device is asleep (screen off). goAsync() alone only
        // holds the broadcast alive; the explicit lock covers the worker that outlives it.
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        // Hand off to a single shared worker rather than spawning a thread per SMS. Under a burst
        // (many messages at once) a thread-per-message design would spawn unbounded threads and can
        // exhaust memory; a serial executor processes them one-by-one, which also matches SQLite's
        // single-writer model — so heavy traffic queues up and drains steadily instead of crashing.
        PIPELINE_EXECUTOR.execute {
            try {
                SmsPipeline.handle(appContext, message)
                AgentEvents.emitSmsReceived(appContext, sender)
            } catch (t: Throwable) {
                // Never crash the receiver — catch Throwable, not just Exception, because a bad
                // regex / class-init failure surfaces as an Error (ExceptionInInitializerError /
                // NoClassDefFoundError), which an `Exception` catch would let through and kill the
                // whole app process on every incoming SMS. Record it so it's visible in-app.
                runCatching {
                    Repository.get(appContext).log("system", "error", "SMS pipeline failed: ${t.message}")
                }
            } finally {
                if (wakeLock.isHeld) runCatching { wakeLock.release() }
                pending.finish()
            }
        }
    }

    private fun hash(sender: String, body: String, timestamp: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$sender|$body|$timestamp".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val SUB_EXTRA = "android.telephony.extra.SUBSCRIPTION_INDEX"
        private const val SLOT_EXTRA = "android.telephony.extra.SLOT_INDEX"
        private const val WAKE_LOCK_TAG = "SmsGatewayAgent:SmsReceiver"
        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L

        // Process-wide serial worker. BroadcastReceiver instances are transient (one per broadcast),
        // so the executor must be static to be shared across every incoming SMS. A daemon thread so
        // it never blocks process shutdown.
        private val PIPELINE_EXECUTOR: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "sms-pipeline").apply { isDaemon = true }
            }
    }
}
