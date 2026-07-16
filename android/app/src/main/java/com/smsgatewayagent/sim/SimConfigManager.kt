package com.smsgatewayagent.sim

import android.content.Context
import com.smsgatewayagent.data.Repository
import com.smsgatewayagent.device.DeviceInfoProvider
import com.smsgatewayagent.model.QueueItem
import com.smsgatewayagent.model.SimConfigRow
import com.smsgatewayagent.queue.QueueScheduler
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the per-SIM capture configuration (record flag + service assignment) and the station
 * profile that is pushed to the server.
 *
 * Matching an inbound SMS is anchored on the physical slot — the only identifier the SMS broadcast
 * reliably carries — while the operator-facing identity is the phone number. A slot with no saved,
 * record-enabled config is ignored entirely ("ignore until configured"), so an unrelated personal
 * SIM never has its messages stored or forwarded.
 */
object SimConfigManager {

    private const val STATION_NAME_KEY = "station_name"
    private const val SYNCED_AT_KEY = "station_config_synced_at"

    fun configForSlot(context: Context, slot: Int): SimConfigRow? =
        Repository.get(context).getSimConfigForSlot(slot)

    /**
     * The capture gate consulted by the pipeline. Default false → ignore until configured.
     *
     * Robustness: some ROMs (notably HyperOS/MIUI, and dual-SIM phones) deliver `SMS_RECEIVED` with
     * neither a slot nor a subscription id, so the pipeline can't resolve which SIM it came from and
     * `slot` arrives as -1. Rather than silently drop a real payment SMS, when the slot is unknown we
     * still record it as long as *some* SIM on this station is set to record — the sender-based rules
     * (bKash/NAGAD) then decide what to do. A payment station should never lose an SMS just because
     * the OS wouldn't name the slot.
     */
    fun shouldRecord(context: Context, slot: Int): Boolean {
        configForSlot(context, slot)?.let { return it.recordEnabled }
        return slot < 0 && anySlotRecords(context)
    }

    /** True when at least one configured SIM slot has recording enabled. */
    private fun anySlotRecords(context: Context): Boolean {
        val configs = Repository.get(context).getSimConfigs()
        for (i in 0 until configs.length()) {
            if (configs.getJSONObject(i).optInt("record_enabled", 0) == 1) return true
        }
        return false
    }

    fun servicesForSlot(context: Context, slot: Int): List<String> =
        configForSlot(context, slot)?.services ?: emptyList()

    fun stationName(context: Context): String =
        Repository.get(context).getSetting(STATION_NAME_KEY, "")?.takeIf { it.isNotBlank() }
            ?: DeviceInfoProvider.deviceId(context)

    fun setStationName(context: Context, name: String) {
        Repository.get(context).setSetting(STATION_NAME_KEY, name.trim())
    }

    /** Compact station identity stamped onto every forwarded payment SMS. */
    fun stationBadge(context: Context): JSONObject = JSONObject().apply {
        put("deviceId", DeviceInfoProvider.deviceId(context))
        put("name", stationName(context))
    }

    /**
     * For the setup UI: every currently-detected SIM overlaid with its saved config, plus any
     * configured-but-currently-absent SIMs (so an operator can still see/edit a slot whose SIM was
     * pulled). The phone number prefers the operator-entered value, falling back to what Android
     * could read.
     */
    fun mergedConfigs(context: Context): JSONArray {
        val repo = Repository.get(context)
        val out = JSONArray()
        val seenSlots = HashSet<Int>()

        for (sim in SimManager.currentSims(context)) {
            seenSlots.add(sim.slot)
            val saved = repo.getSimConfigForSlot(sim.slot)
            val savedNumber = saved?.phoneNumber?.takeIf { it.isNotBlank() }
            out.put(JSONObject().apply {
                put("slot", sim.slot)
                put("subscriptionId", sim.subscriptionId)
                put("carrier", sim.carrier ?: JSONObject.NULL)
                put("iccid", sim.iccid ?: JSONObject.NULL)
                put("phoneNumber", (savedNumber ?: sim.phoneNumber) ?: JSONObject.NULL)
                // Where the shown number came from, so the UI can explain a blank (carrier vs permission).
                put("phoneNumberSource", if (savedNumber != null) "manual" else sim.phoneNumberSource)
                put("displayName", sim.displayName ?: JSONObject.NULL)
                put("recordEnabled", saved?.recordEnabled ?: false)
                put("services", JSONArray(saved?.services ?: emptyList<String>()))
                put("detected", true)
            })
        }

        // Configured slots whose SIM isn't currently present.
        val configs = repo.getSimConfigs()
        for (i in 0 until configs.length()) {
            val row = configs.getJSONObject(i)
            val slot = row.optInt("slot", -1)
            if (slot < 0 || seenSlots.contains(slot)) continue
            out.put(JSONObject().apply {
                put("slot", slot)
                put("subscriptionId", row.optInt("subscription_id", -1))
                put("carrier", row.opt("carrier") ?: JSONObject.NULL)
                put("iccid", row.opt("iccid") ?: JSONObject.NULL)
                put("phoneNumber", row.opt("phone_number") ?: JSONObject.NULL)
                put("phoneNumberSource", if (!row.optString("phone_number").isNullOrBlank()) "manual" else "unavailable_carrier")
                put("displayName", JSONObject.NULL)
                put("recordEnabled", row.optInt("record_enabled", 0) == 1)
                put("services", JSONArray(row.optString("services", "[]")))
                put("detected", false)
            })
        }
        return out
    }

    /**
     * Reconcile saved per-slot configs against the SIMs physically present right now.
     *
     * Preferences are **slot-sticky**: the record flag and service map belong to the physical slot,
     * so swapping the SIM in slot 1 keeps "slot 1 records bKash" untouched. What tracks the SIM is
     * its *identity* — subscriptionId, carrier, iccid and phone number — which we refresh here.
     *
     * The phone number is handled carefully because it drives payment matching on the server, and
     * we never destroy the operator's hand-entered number on a guess:
     *  - We only *replace* the number when we can positively prove a different SIM is in the slot,
     *    i.e. both the old and new ICCIDs are readable and differ. Then the old number belonged to
     *    the removed SIM, so we adopt the new SIM's auto-readable number, or blank if the carrier
     *    can't tell us — a blank is safer than a confidently-wrong number for auto-approval.
     *  - Otherwise (same SIM, or ICCID unreadable — which is the norm on Android 10+, where ICCID is
     *    a restricted identifier and comes back blank) we keep the operator's number, and only
     *    backfill it when it was blank and Android can now read one.
     *
     * Returns true if any row changed (so the caller can push the refreshed profile to the server).
     */
    fun reconcileWithCurrentSims(context: Context): Boolean {
        val repo = Repository.get(context)
        var changed = false
        for (sim in SimManager.currentSims(context)) {
            val saved = repo.getSimConfigForSlot(sim.slot) ?: continue // only touch configured slots
            // Positive proof the physical SIM changed: both ICCIDs readable and different. When ICCID
            // is unreadable (blank) we can't tell, so we must NOT assume a swap and wipe the number.
            val simDefinitelyChanged =
                !saved.iccid.isNullOrBlank() && !sim.iccid.isNullOrBlank() && saved.iccid != sim.iccid
            val autoRead = sim.phoneNumber?.takeIf { it.isNotBlank() } // non-blank only when source=="sim"
            val newNumber = when {
                simDefinitelyChanged -> autoRead                         // replace; blank if unreadable
                !saved.phoneNumber.isNullOrBlank() -> saved.phoneNumber  // keep operator's number
                else -> autoRead                                         // was blank: backfill if any
            }
            val identityChanged = saved.subscriptionId != sim.subscriptionId ||
                (saved.iccid ?: "") != (sim.iccid ?: "") ||
                (saved.carrier ?: "") != (sim.carrier ?: "") ||
                (saved.phoneNumber ?: "") != (newNumber ?: "")
            if (!identityChanged) continue
            repo.upsertSimConfig(JSONObject().apply {
                put("slot", sim.slot)
                put("subscriptionId", sim.subscriptionId)
                put("iccid", sim.iccid ?: JSONObject.NULL)
                put("carrier", sim.carrier ?: JSONObject.NULL)
                put("phoneNumber", newNumber ?: JSONObject.NULL)
                put("recordEnabled", saved.recordEnabled)      // slot-sticky
                put("services", JSONArray(saved.services))     // slot-sticky
            })
            changed = true
            repo.log(
                "sim", "info",
                "Reconciled slot ${sim.slot}: identity refreshed${if (simDefinitelyChanged) " (SIM changed)" else ""}"
            )
        }
        return changed
    }

    /** Names of enabled services in the catalog. */
    fun enabledServiceNames(context: Context): JSONArray {
        val repo = Repository.get(context)
        val rows = repo.getServices()
        val out = JSONArray()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            if (r.optInt("enabled", 1) == 1) out.put(r.optString("name"))
        }
        return out
    }

    /** The full station profile: what the server's station manager ingests. */
    fun buildStationConfig(context: Context): JSONObject {
        val repo = Repository.get(context)
        val device = DeviceInfoProvider.toJson(context)
        val station = JSONObject().apply {
            put("deviceId", device.optString("deviceId"))
            put("name", stationName(context))
            put("model", device.optString("model"))
            put("manufacturer", device.optString("manufacturer"))
            put("androidVersion", device.optString("androidVersion"))
            put("appVersion", device.optString("appVersion"))
        }

        val sims = JSONArray()
        val configs = repo.getSimConfigs()
        for (i in 0 until configs.length()) {
            val row = configs.getJSONObject(i)
            sims.put(JSONObject().apply {
                put("slot", row.optInt("slot"))
                put("phoneNumber", row.opt("phone_number") ?: JSONObject.NULL)
                put("carrier", row.opt("carrier") ?: JSONObject.NULL)
                put("subscriptionId", row.optInt("subscription_id", -1))
                put("record", row.optInt("record_enabled", 0) == 1)
                put("services", JSONArray(row.optString("services", "[]")))
            })
        }

        return JSONObject().apply {
            put("event", "station_config")
            put("station", station)
            put("services", enabledServiceNames(context))
            put("sims", sims)
            put("timestamp", System.currentTimeMillis())
        }
    }

    /**
     * Pushes the current station profile to the default webhook (the "server"), reusing the same
     * durable queue as payment forwarding. Returns false when no destination is configured yet.
     */
    fun syncStationConfig(context: Context): Boolean {
        val repo = Repository.get(context)
        val webhooks = repo.getEnabledWebhooks()
        if (webhooks.isEmpty()) return false
        val payload = buildStationConfig(context).toString()
        val stamp = System.currentTimeMillis()
        for (webhook in webhooks) {
            repo.enqueue(
                QueueItem(
                    messageId = null,
                    webhookId = webhook.id,
                    webhookUrl = webhook.url,
                    payload = payload,
                    idempotencyKey = "station_config_${stamp}_${webhook.id}"
                )
            )
        }
        repo.setSetting(SYNCED_AT_KEY, stamp.toString())
        QueueScheduler.scheduleNow(context)
        repo.log("sim", "info", "Station config queued for sync to ${webhooks.size} webhook(s)")
        return true
    }

    /**
     * Re-push the station profile after a reboot, but only for a station that was already set up
     * and synced before — so a fresh, unconfigured install doesn't spam the server on every boot.
     * Keeps the server's view current after the OS may have renumbered subscriptions.
     */
    fun resyncIfConfigured(context: Context): Boolean {
        val repo = Repository.get(context)
        if (repo.getSetting(SYNCED_AT_KEY) == null) return false
        return syncStationConfig(context)
    }
}
