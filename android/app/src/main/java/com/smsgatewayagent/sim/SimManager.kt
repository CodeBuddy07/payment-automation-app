package com.smsgatewayagent.sim

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.smsgatewayagent.data.Repository
import com.smsgatewayagent.device.DeviceInfoProvider
import com.smsgatewayagent.model.QueueItem
import com.smsgatewayagent.model.SimInfo
import com.smsgatewayagent.queue.QueueScheduler
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads live SIM/subscription state, detects insert/remove/change/swap events against the last
 * snapshot, records them to history and (optionally) forwards them to a webhook.
 */
object SimManager {

    private const val SNAPSHOT_KEY = "sim_snapshot"
    private const val PUSH_ENABLED_KEY = "sim_push_enabled"

    fun currentSims(context: Context): List<SimInfo> {
        if (!hasPhonePermission(context)) return emptyList()
        return try {
            val sm = ContextCompat.getSystemService(context, SubscriptionManager::class.java) ?: return emptyList()
            @Suppress("MissingPermission")
            val list: List<SubscriptionInfo> = sm.activeSubscriptionInfoList ?: emptyList()
            list.map { it.toSimInfo(context, sm) }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun currentSimsJson(context: Context): JSONArray =
        JSONArray().apply { currentSims(context).forEach { put(it.toJson()) } }

    fun simInfoForSubscription(context: Context, subscriptionId: Int): JSONObject? =
        currentSims(context).firstOrNull { it.subscriptionId == subscriptionId }?.toJson()

    /**
     * Resolve the physical slot index for a subscription id using the live subscription list.
     * The inbound SMS broadcast reliably carries the subscription id but frequently omits the slot
     * extra, so the record gate must map sub → slot itself. Returns -1 when it can't be resolved.
     */
    fun slotForSubscription(context: Context, subscriptionId: Int): Int {
        if (subscriptionId < 0) return -1
        return currentSims(context).firstOrNull { it.subscriptionId == subscriptionId }?.slot ?: -1
    }

    /** The slot of the only active SIM, or -1 when there isn't exactly one (can't disambiguate). */
    fun soleActiveSlot(context: Context): Int {
        val sims = currentSims(context)
        return if (sims.size == 1) sims[0].slot else -1
    }

    /**
     * Compares the current SIM layout with the stored snapshot, recording any differences and
     * pushing a `sim_changed` event to the default webhook when enabled. Returns the events found.
     */
    fun detectChanges(context: Context): JSONArray {
        val repo = Repository.get(context)
        val current = currentSimsJson(context)
        val previousRaw = repo.getSetting(SNAPSHOT_KEY)
        val previous = previousRaw?.let { JSONArray(it) } ?: JSONArray()

        val events = diff(previous, current)
        for (i in 0 until events.length()) {
            val ev = events.getJSONObject(i)
            repo.insertSimEvent(
                event = ev.optString("event"),
                slot = ev.optInt("slot", -1),
                subscriptionId = ev.optInt("subscriptionId", -1),
                carrier = ev.optJSONObject("newSim")?.optString("carrier"),
                iccid = ev.optJSONObject("newSim")?.optString("iccid"),
                phoneNumber = ev.optJSONObject("newSim")?.optString("phoneNumber"),
                oldSnapshot = ev.optJSONObject("oldSim")?.toString(),
                newSnapshot = ev.optJSONObject("newSim")?.toString()
            )
        }

        repo.setSetting(SNAPSHOT_KEY, current.toString())

        if (events.length() > 0 && repo.getSetting(PUSH_ENABLED_KEY, "false") == "true") {
            pushSimChange(context, events)
        }
        return events
    }

    private fun diff(previous: JSONArray, current: JSONArray): JSONArray {
        val out = JSONArray()
        val prevBySlot = HashMap<Int, JSONObject>()
        val curBySlot = HashMap<Int, JSONObject>()
        for (i in 0 until previous.length()) previous.getJSONObject(i).let { prevBySlot[it.optInt("slot")] = it }
        for (i in 0 until current.length()) current.getJSONObject(i).let { curBySlot[it.optInt("slot")] = it }

        val slots = prevBySlot.keys + curBySlot.keys
        for (slot in slots) {
            val before = prevBySlot[slot]
            val after = curBySlot[slot]
            val event = when {
                before == null && after != null -> "inserted"
                before != null && after == null -> "removed"
                before != null && after != null && before.optString("iccid") != after.optString("iccid") -> "changed"
                else -> null
            }
            if (event != null) {
                out.put(JSONObject().apply {
                    put("event", event)
                    put("slot", slot)
                    put("subscriptionId", (after ?: before)?.optInt("subscriptionId") ?: -1)
                    put("oldSim", before ?: JSONObject.NULL)
                    put("newSim", after ?: JSONObject.NULL)
                })
            }
        }
        // Detect swap: same ICCIDs present but on different slots.
        if (sameIccidSet(previous, current) && slotsDiffer(previous, current) && out.length() == 0) {
            out.put(JSONObject().apply { put("event", "swapped") })
        }
        return out
    }

    private fun pushSimChange(context: Context, events: JSONArray) {
        val repo = Repository.get(context)
        val webhook = repo.getDefaultWebhook() ?: return
        val payload = JSONObject().apply {
            put("event", "sim_changed")
            put("changes", events)
            put("timestamp", System.currentTimeMillis())
            put("deviceId", DeviceInfoProvider.deviceId(context))
        }
        repo.enqueue(
            QueueItem(
                messageId = null,
                webhookId = webhook.id,
                webhookUrl = webhook.url,
                payload = payload.toString(),
                idempotencyKey = "sim_${System.currentTimeMillis()}"
            )
        )
        QueueScheduler.scheduleNow(context)
    }

    private fun sameIccidSet(a: JSONArray, b: JSONArray): Boolean {
        fun iccids(arr: JSONArray) = (0 until arr.length()).map { arr.getJSONObject(it).optString("iccid") }.toSet()
        return a.length() > 0 && iccids(a) == iccids(b)
    }

    private fun slotsDiffer(a: JSONArray, b: JSONArray): Boolean {
        fun map(arr: JSONArray) = (0 until arr.length()).associate {
            arr.getJSONObject(it).optString("iccid") to arr.getJSONObject(it).optInt("slot")
        }
        return map(a) != map(b)
    }

    private fun hasPhonePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /**
     * True when a permission that lets us read the subscriber number is granted. On Android 13+
     * `getPhoneNumber()` accepts READ_PHONE_NUMBERS *or* READ_SMS (READ_PHONE_STATE alone is not
     * enough), so we treat either as sufficient.
     */
    private fun canReadPhoneNumber(context: Context): Boolean {
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return granted(Manifest.permission.READ_PHONE_NUMBERS) || granted(Manifest.permission.READ_SMS)
    }

    private fun SubscriptionInfo.toSimInfo(context: Context, sm: SubscriptionManager): SimInfo {
        val repo = Repository.get(context)
        val override = repo.getSetting("phone_number_override_$subscriptionId")?.takeIf { it.isNotBlank() }
        val number = override ?: resolveNumber(context, sm)
        val source = when {
            override != null -> "manual"
            !number.isNullOrBlank() -> "sim"
            // Only blame permissions on 13+, where getPhoneNumber() specifically needs
            // READ_PHONE_NUMBERS/READ_SMS. On older APIs the read is gated by READ_PHONE_STATE,
            // which is already granted if we reached this point.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !canReadPhoneNumber(context) ->
                "unavailable_permission"
            else -> "unavailable_carrier"
        }
        return SimInfo(
            slot = simSlotIndex,
            subscriptionId = subscriptionId,
            carrier = carrierName?.toString(),
            iccid = runCatching { iccId }.getOrNull(),
            phoneNumber = number,
            phoneNumberSource = source,
            displayName = displayName?.toString(),
            countryIso = countryIso
        )
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun SubscriptionInfo.resolveNumber(context: Context, sm: SubscriptionManager): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sm.getPhoneNumber(subscriptionId).takeIf { it.isNotBlank() } ?: number
        } else number
    } catch (_: Exception) {
        null
    }
}
