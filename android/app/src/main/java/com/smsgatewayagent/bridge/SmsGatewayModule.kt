package com.smsgatewayagent.bridge

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.smsgatewayagent.data.Repository
import com.smsgatewayagent.device.DeviceInfoProvider
import com.smsgatewayagent.model.QueueItem
import com.smsgatewayagent.model.Rule
import com.smsgatewayagent.model.SmsMessage
import com.smsgatewayagent.pipeline.RuleMatcher
import com.smsgatewayagent.pipeline.SmsPipeline
import com.smsgatewayagent.processor.ProcessorContext
import com.smsgatewayagent.processor.ProcessorRegistry
import com.smsgatewayagent.processor.TemplateEngine
import com.smsgatewayagent.queue.QueueScheduler
import com.smsgatewayagent.queue.WebhookSender
import com.smsgatewayagent.security.SecurePrefs
import com.smsgatewayagent.service.SmsAgentForegroundService
import com.smsgatewayagent.sim.SimConfigManager
import com.smsgatewayagent.sim.SimManager
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * The sole bridge between the React Native UI and the native reliability core. It never owns
 * state itself — every call delegates to [Repository] and the pipeline/queue components, so the
 * UI is a thin, swappable client over the same SQLite store the background workers use.
 *
 * JSON is passed as strings in both directions to keep one serialization format end-to-end.
 */
class SmsGatewayModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val repo get() = Repository.get(reactContext)
    private val secure get() = SecurePrefs.get(reactContext)

    init {
        AgentEvents.reactContext = reactContext
        // Make sure the safety-net drain and SIM baseline exist whenever the UI starts, and seed
        // the starter services/rules once so the app is usable without hand-training everything.
        runCatching {
            Repository.get(reactContext).seedDefaultsIfNeeded()
            QueueScheduler.schedulePeriodic(reactContext)
            SimManager.detectChanges(reactContext)
        }
    }

    override fun getName() = "SmsGateway"

    private inline fun guard(promise: Promise, block: () -> Any?) {
        try {
            // The RN bridge cannot marshal a java.lang.Long ("Cannot convert argument of type class
            // java.lang.Long"), and every id-returning DB call (insert/upsert) hands back a Long.
            // Resolving that would reject the promise even though the write succeeded — which is why
            // saves appeared to fail, duplicated, or never confirmed. Coerce Long → Double (JS's
            // only number type; row ids are well within its exact-integer range) so it marshals.
            when (val result = block()) {
                is Long -> promise.resolve(result.toDouble())
                is Int -> promise.resolve(result.toDouble())
                is Unit -> promise.resolve(null)
                else -> promise.resolve(result)
            }
        } catch (e: Throwable) {
            // Catch Throwable, not just Exception: a class-init failure (e.g. a bad regex in a
            // static initializer) arrives as an Error and would otherwise crash the app process on
            // the bridge thread instead of rejecting the JS promise. Rejecting lets the UI show it.
            promise.reject("SMS_GATEWAY_ERR", e.message ?: e.toString(), e)
        }
    }

    /** Query the system SMS inbox provider for the newest [limit] messages (address/body/date). */
    private fun readDeviceInbox(limit: Int): JSONArray {
        val out = JSONArray()
        if (ContextCompat.checkSelfPermission(reactContext, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) return out
        val cap = if (limit in 1..1000) limit else 200
        val cols = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE
        )
        reactContext.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, cols, null, null, "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addrIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext() && out.length() < cap) {
                out.put(JSONObject().apply {
                    put("id", c.getLong(idIdx))
                    put("sender", c.getString(addrIdx) ?: "")
                    put("body", c.getString(bodyIdx) ?: "")
                    put("timestamp", c.getLong(dateIdx))
                })
            }
        }
        return out
    }

    // -------------------------------------------------------------- Device info
    @ReactMethod
    fun getDeviceInfo(promise: Promise) = guard(promise) { DeviceInfoProvider.toJson(reactContext).toString() }

    // ----------------------------------------------------------------- Messages
    @ReactMethod
    fun getMessages(search: String?, status: String?, limit: Int, offset: Int, promise: Promise) =
        guard(promise) { repo.queryMessages(search, status, limit, offset).toString() }

    /**
     * Reads the device's real SMS inbox (content://sms/inbox), newest first, so the operator can
     * train templates on *historical* messages — including ones received before the app was
     * installed, which the app's own capture table never had. Requires READ_SMS (already held);
     * returns an empty array if it isn't granted.
     */
    @ReactMethod
    fun getInboxMessages(limit: Int, promise: Promise) = guard(promise) { readDeviceInbox(limit).toString() }

    @ReactMethod
    fun getLatestMessage(promise: Promise) = guard(promise) { repo.latestMessage()?.toString() }

    @ReactMethod
    fun getMessageCount(promise: Promise) = guard(promise) { repo.messageCount() }

    @ReactMethod
    fun exportMessages(promise: Promise) =
        guard(promise) { repo.queryMessages(null, null, 1000, 0).toString() }

    // -------------------------------------------------------------------- Rules
    @ReactMethod
    fun getRules(promise: Promise) = guard(promise) { repo.getRules().toString() }

    @ReactMethod
    fun saveRule(json: String, promise: Promise) = guard(promise) {
        val obj = JSONObject(json)
        // Pre-compile template → regex so the background pipeline never has to.
        if (obj.optString("processorType") == "template") {
            val template = obj.optString("template")
            if (template.isNotBlank() && obj.optString("regex").isBlank()) {
                obj.put("regex", TemplateEngine.templateToRegex(template))
            }
        }
        repo.upsertRule(obj)
    }

    @ReactMethod
    fun deleteRule(id: Double, promise: Promise) = guard(promise) { repo.deleteRule(id.toLong()) }

    @ReactMethod
    fun setRuleEnabled(id: Double, enabled: Boolean, promise: Promise) =
        guard(promise) { repo.setRuleEnabled(id.toLong(), enabled) }

    // ----------------------------------------------------------------- Webhooks
    @ReactMethod
    fun getWebhooks(promise: Promise) = guard(promise) {
        val arr = repo.getWebhooks()
        // Reveal only whether a secret is set, never the secret itself.
        for (i in 0 until arr.length()) {
            val w = arr.getJSONObject(i)
            w.put("hasBearer", secure.has(w.optString("bearer_ref").takeIf { it.isNotBlank() }))
            w.put("hasHmac", secure.has(w.optString("hmac_ref").takeIf { it.isNotBlank() }))
        }
        arr.toString()
    }

    @ReactMethod
    fun saveWebhook(json: String, promise: Promise) = guard(promise) {
        val obj = JSONObject(json)
        val id = repo.upsertWebhook(obj)
        // Persist any provided plaintext secrets into Keystore-backed storage.
        if (obj.has("bearerToken")) {
            val ref = obj.optString("bearer_ref", "bearer_$id").ifBlank { "bearer_$id" }
            val token = obj.optString("bearerToken")
            secure.put(ref, token.ifBlank { null })
            obj.put("id", id); obj.put("bearerRef", token.ifBlank { null }?.let { ref })
        }
        if (obj.has("hmacSecret")) {
            val ref = obj.optString("hmac_ref", "hmac_$id").ifBlank { "hmac_$id" }
            val secret = obj.optString("hmacSecret")
            secure.put(ref, secret.ifBlank { null })
            obj.put("id", id); obj.put("hmacRef", secret.ifBlank { null }?.let { ref })
        }
        if (obj.has("bearerToken") || obj.has("hmacSecret")) repo.upsertWebhook(obj)
        id
    }

    @ReactMethod
    fun deleteWebhook(id: Double, promise: Promise) = guard(promise) {
        secure.remove("bearer_${id.toLong()}")
        secure.remove("hmac_${id.toLong()}")
        repo.deleteWebhook(id.toLong())
    }

    @ReactMethod
    fun testWebhook(webhookId: Double, payload: String, promise: Promise) = guard(promise) {
        val webhook = repo.getWebhook(webhookId.toLong())
            ?: throw IllegalArgumentException("Webhook not found")
        val item = QueueItem(
            messageId = null, webhookId = webhook.id, webhookUrl = webhook.url,
            payload = payload, idempotencyKey = "test_${UUID.randomUUID()}"
        )
        val result = WebhookSender.send(reactContext, item)
        JSONObject().apply {
            put("success", result.success)
            put("statusCode", result.statusCode)
            put("error", result.error ?: JSONObject.NULL)
        }.toString()
    }

    // -------------------------------------------------------------------- Queue
    @ReactMethod
    fun getQueue(status: String?, promise: Promise) = guard(promise) { repo.getQueue(status).toString() }

    @ReactMethod
    fun getQueueStats(promise: Promise) = guard(promise) { repo.queueStats().toString() }

    @ReactMethod
    fun retryQueueItem(id: Double, promise: Promise) = guard(promise) {
        repo.retryQueueItem(id.toLong()); QueueScheduler.scheduleNow(reactContext)
    }

    @ReactMethod
    fun retryAllDead(promise: Promise) = guard(promise) {
        repo.retryAllDead(); QueueScheduler.scheduleNow(reactContext)
    }

    @ReactMethod
    fun deleteQueueItem(id: Double, promise: Promise) = guard(promise) { repo.deleteQueueItem(id.toLong()) }

    @ReactMethod
    fun processQueueNow(promise: Promise) = guard(promise) { QueueScheduler.scheduleNow(reactContext) }

    // ----------------------------------------------------------------- Settings
    @ReactMethod
    fun getAllSettings(promise: Promise) = guard(promise) { repo.getAllSettings().toString() }

    @ReactMethod
    fun setSetting(key: String, value: String?, promise: Promise) =
        guard(promise) { repo.setSetting(key, value) }

    // ---------------------------------------------------------------------- SIM
    @ReactMethod
    fun getSims(promise: Promise) = guard(promise) { SimManager.currentSimsJson(reactContext).toString() }

    @ReactMethod
    fun getSimHistory(promise: Promise) = guard(promise) { repo.getSimHistory().toString() }

    @ReactMethod
    fun detectSimChanges(promise: Promise) = guard(promise) { SimManager.detectChanges(reactContext).toString() }

    // -------------------------------------------------- SIM config / station
    /** Detected SIMs merged with their saved record/service config, for the setup screen. */
    @ReactMethod
    fun getSimConfigs(promise: Promise) = guard(promise) { SimConfigManager.mergedConfigs(reactContext).toString() }

    /** Persist one slot's config: { slot, subscriptionId?, carrier?, iccid?, phoneNumber?, recordEnabled, services[] }. */
    @ReactMethod
    fun saveSimConfig(json: String, promise: Promise) = guard(promise) {
        repo.upsertSimConfig(JSONObject(json)); true
    }

    @ReactMethod
    fun getServices(promise: Promise) = guard(promise) { repo.getServices().toString() }

    @ReactMethod
    fun addService(name: String, promise: Promise) = guard(promise) { repo.addService(name) }

    @ReactMethod
    fun setServiceEnabled(id: Double, enabled: Boolean, promise: Promise) =
        guard(promise) { repo.setServiceEnabled(id.toLong(), enabled) }

    @ReactMethod
    fun deleteService(id: Double, promise: Promise) = guard(promise) { repo.deleteService(id.toLong()) }

    @ReactMethod
    fun getStationProfile(promise: Promise) = guard(promise) {
        DeviceInfoProvider.toJson(reactContext).apply {
            put("name", SimConfigManager.stationName(reactContext))
            put("syncedAt", repo.getSetting("station_config_synced_at")?.toLongOrNull() ?: JSONObject.NULL)
        }.toString()
    }

    @ReactMethod
    fun setStationName(name: String, promise: Promise) =
        guard(promise) { SimConfigManager.setStationName(reactContext, name); true }

    /** Build and enqueue the station profile to the server (default webhook). */
    @ReactMethod
    fun syncStationConfig(promise: Promise) = guard(promise) { SimConfigManager.syncStationConfig(reactContext) }

    // ------------------------------------------------------------- Processors
    @ReactMethod
    fun getProcessors(promise: Promise) = guard(promise) { ProcessorRegistry.describe().toString() }

    // -------------------------------------------------------------- Training
    @ReactMethod
    fun generateTemplate(sample: String, promise: Promise) =
        guard(promise) { TemplateEngine.generateTemplate(sample) }

    @ReactMethod
    fun trainTemplate(samplesJson: String, promise: Promise) = guard(promise) {
        val arr = JSONArray(samplesJson)
        val samples = (0 until arr.length()).map { arr.getString(it) }
        TemplateEngine.train(samples).toString()
    }

    @ReactMethod
    fun testRule(ruleJson: String, sender: String, body: String, promise: Promise) = guard(promise) {
        val rule = jsonToRule(JSONObject(ruleJson))
        val message = transientMessage(sender, body)
        val out = JSONObject()
        if (!RuleMatcher.senderMatches(rule, sender)) {
            out.put("matched", false); out.put("reason", "Sender did not match")
        } else {
            val processor = ProcessorRegistry.get(rule.processorType)
            if (processor == null) {
                out.put("matched", false); out.put("reason", "Unknown processor")
            } else {
                val r = processor.process(message, rule, ProcessorContext())
                out.put("matched", r.matched)
                out.put("data", r.data)
                out.put("errors", JSONArray(r.errors))
            }
        }
        out.toString()
    }

    @ReactMethod
    fun testRuleAgainstHistory(ruleJson: String, promise: Promise) = guard(promise) {
        val rule = jsonToRule(JSONObject(ruleJson))
        val processor = ProcessorRegistry.get(rule.processorType)
        val messages = repo.queryMessages(null, null, 1000, 0)
        var total = 0
        val samples = JSONArray()
        if (processor != null) {
            for (i in 0 until messages.length()) {
                val m = messages.getJSONObject(i)
                val sender = m.optString("sender")
                if (!RuleMatcher.senderMatches(rule, sender)) continue
                val msg = transientMessage(sender, m.optString("body"))
                val r = processor.process(msg, rule, ProcessorContext())
                if (r.matched) {
                    total++
                    if (samples.length() < 10) samples.put(JSONObject().apply {
                        put("body", m.optString("body"))
                        put("data", r.data)
                    })
                }
            }
        }
        JSONObject().apply { put("totalMatches", total); put("samples", samples) }.toString()
    }

    @ReactMethod
    fun testMessage(sender: String, body: String, promise: Promise) =
        guard(promise) { SmsPipeline.test(reactContext, transientMessage(sender, body)).toString() }

    // -------------------------------------------------------------- Diagnostics
    @ReactMethod
    fun getDiagnostics(promise: Promise) = guard(promise) {
        JSONObject().apply {
            put("permissions", JSONObject().apply {
                put("receiveSms", granted(Manifest.permission.RECEIVE_SMS))
                put("readSms", granted(Manifest.permission.READ_SMS))
                put("readPhoneState", granted(Manifest.permission.READ_PHONE_STATE))
                put("postNotifications",
                    if (Build.VERSION.SDK_INT >= 33) granted("android.permission.POST_NOTIFICATIONS") else true)
            })
            put("batteryOptimizationIgnored", isIgnoringBattery())
            put("queue", repo.queueStats())
            put("messageCount", repo.messageCount())
            put("lastReceivedSms", repo.latestMessage() ?: JSONObject.NULL)
            put("lastWebhookSuccess", repo.lastLogOf("webhook", "info") ?: JSONObject.NULL)
            put("lastWebhookFailure", repo.lastLogOf("webhook", "error") ?: JSONObject.NULL)
            put("lastSync", repo.getSetting("last_sync") ?: JSONObject.NULL)
            put("processorFailures", repo.countLogs("processor", "error"))
            put("webhookFailures", repo.countLogs("webhook", "error"))
            put("device", DeviceInfoProvider.toJson(reactContext))
        }.toString()
    }

    @ReactMethod
    fun getDiagnosticsLog(limit: Int, promise: Promise) =
        guard(promise) { repo.getDiagnosticsLog(limit).toString() }

    // --------------------------------------------------- Permissions / power
    @ReactMethod
    fun isIgnoringBatteryOptimizations(promise: Promise) = guard(promise) { isIgnoringBattery() }

    @ReactMethod
    fun requestIgnoreBatteryOptimizations(promise: Promise) = guard(promise) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${reactContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        reactContext.startActivity(intent)
        true
    }

    @ReactMethod
    fun openAppSettings(promise: Promise) = guard(promise) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${reactContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        reactContext.startActivity(intent)
        true
    }

    // -------------------------------------------------- Guided onboarding setup
    /** One call that returns every signal the onboarding flow needs to render its checklist. */
    @ReactMethod
    fun getSetupStatus(promise: Promise) = guard(promise) {
        JSONObject().apply {
            put("receiveSms", granted(Manifest.permission.RECEIVE_SMS))
            put("readSms", granted(Manifest.permission.READ_SMS))
            put("readPhoneState", granted(Manifest.permission.READ_PHONE_STATE))
            put("postNotifications",
                if (Build.VERSION.SDK_INT >= 33) granted("android.permission.POST_NOTIFICATIONS") else true)
            put("notificationsEnabled", NotificationManagerCompat.from(reactContext).areNotificationsEnabled())
            put("batteryOptimizationIgnored", isIgnoringBattery())
            put("backgroundRestricted", isBackgroundRestricted())
            put("manufacturer", Build.MANUFACTURER)
            put("onboardingDone", repo.getSetting("onboarding_done", "false") == "true")
        }.toString()
    }

    @ReactMethod
    fun areNotificationsEnabled(promise: Promise) =
        guard(promise) { NotificationManagerCompat.from(reactContext).areNotificationsEnabled() }

    @ReactMethod
    fun openNotificationSettings(promise: Promise) = guard(promise) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, reactContext.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        reactContext.startActivity(intent)
        true
    }

    @ReactMethod
    fun isBackgroundRestricted(promise: Promise) = guard(promise) { isBackgroundRestricted() }

    /**
     * Best-effort jump to the OEM auto-start / background manager (Xiaomi, Oppo, Vivo, Huawei,
     * Samsung, …). Falls back to the standard app settings page if no known screen resolves.
     */
    @ReactMethod
    fun openAutoStartSettings(promise: Promise) = guard(promise) {
        val candidates = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
        )
        for (component in candidates) {
            val intent = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(reactContext.packageManager) != null) {
                try {
                    reactContext.startActivity(intent)
                    return@guard true
                } catch (_: Exception) { /* try next */ }
            }
        }
        // Fallback: standard app details page.
        reactContext.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${reactContext.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }

    // --------------------------------------------------- Foreground service
    @ReactMethod
    fun startForegroundService(promise: Promise) = guard(promise) {
        repo.setSetting("foreground_enabled", "true")
        ContextCompat.startForegroundService(reactContext, Intent(reactContext, SmsAgentForegroundService::class.java))
        true
    }

    @ReactMethod
    fun stopForegroundService(promise: Promise) = guard(promise) {
        repo.setSetting("foreground_enabled", "false")
        reactContext.stopService(Intent(reactContext, SmsAgentForegroundService::class.java))
        true
    }

    // RN event-emitter bookkeeping (no-ops, required to silence NativeEventEmitter warnings).
    @ReactMethod fun addListener(eventName: String) { /* handled by DeviceEventManager */ }
    @ReactMethod fun removeListeners(count: Int) { /* handled by DeviceEventManager */ }

    // ------------------------------------------------------------------ Helpers
    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(reactContext, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun isIgnoringBattery(): Boolean {
        val pm = reactContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(reactContext.packageName)
    }

    private fun isBackgroundRestricted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val am = reactContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.isBackgroundRestricted
    }

    private fun transientMessage(sender: String, body: String) = SmsMessage(
        hash = "", sender = sender, body = body, timestamp = System.currentTimeMillis(),
        simSlot = -1, subscriptionId = -1, deviceId = DeviceInfoProvider.deviceId(reactContext), phoneNumber = null
    )

    private fun jsonToRule(o: JSONObject) = Rule(
        id = o.optLong("id", 0),
        name = o.optString("name"),
        senderPattern = o.optString("senderPattern"),
        senderMatchType = o.optString("senderMatchType", "contains"),
        processorType = o.optString("processorType"),
        template = o.optString("template").ifBlank { null },
        regex = o.optString("regex").ifBlank { null },
        config = o.optString("config").ifBlank { null },
        webhookId = if (o.isNull("webhookId")) null else o.optLong("webhookId"),
        payloadTemplate = o.optString("payloadTemplate").ifBlank { null },
        enabled = o.optBoolean("enabled", true),
        priority = o.optInt("priority", 0)
    )
}
