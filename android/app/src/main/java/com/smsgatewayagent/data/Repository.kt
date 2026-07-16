package com.smsgatewayagent.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.smsgatewayagent.model.QueueItem
import com.smsgatewayagent.model.Rule
import com.smsgatewayagent.model.SimConfigRow
import com.smsgatewayagent.model.SmsMessage
import com.smsgatewayagent.model.Webhook
import org.json.JSONArray
import org.json.JSONObject

/**
 * The only class that issues SQL. Everything else (pipeline, queue worker, RN bridge)
 * depends on this narrow surface, keeping persistence concerns in one place (SRP / DIP).
 */
class Repository private constructor(context: Context) {

    private val db = AppDatabase.get(context)

    // ----------------------------------------------------------------- Messages
    /** Insert if the hash is new. Returns the row id, or null when it is a duplicate. */
    fun insertMessageIfNew(m: SmsMessage): Long? {
        val values = ContentValues().apply {
            put(Schema.Messages.HASH, m.hash)
            put(Schema.Messages.SENDER, m.sender)
            put(Schema.Messages.BODY, m.body)
            put(Schema.Messages.TIMESTAMP, m.timestamp)
            put(Schema.Messages.SIM_SLOT, m.simSlot)
            put(Schema.Messages.SUBSCRIPTION_ID, m.subscriptionId)
            put(Schema.Messages.DEVICE_ID, m.deviceId)
            put(Schema.Messages.PHONE_NUMBER, m.phoneNumber)
            put(Schema.Messages.WEBHOOK_STATUS, m.webhookStatus)
            put(Schema.Messages.CREATED_AT, m.createdAt)
        }
        val id = db.writableDatabase.insertWithOnConflict(
            Schema.Messages.TABLE, null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
        if (id == -1L) return null
        trimMessages()
        return id
    }

    fun updateMessageProcessing(id: Long, ruleId: Long?, processorType: String?, parsedData: String?) {
        val values = ContentValues().apply {
            put(Schema.Messages.MATCHED_RULE_ID, ruleId)
            put(Schema.Messages.PROCESSOR_TYPE, processorType)
            put(Schema.Messages.PARSED_DATA, parsedData)
        }
        db.writableDatabase.update(Schema.Messages.TABLE, values, "${Schema.Messages.ID}=?", arrayOf(id.toString()))
    }

    fun updateMessageWebhookStatus(messageId: Long, status: String, retryCount: Int) {
        val values = ContentValues().apply {
            put(Schema.Messages.WEBHOOK_STATUS, status)
            put(Schema.Messages.RETRY_COUNT, retryCount)
        }
        db.writableDatabase.update(Schema.Messages.TABLE, values, "${Schema.Messages.ID}=?", arrayOf(messageId.toString()))
    }

    /** Keep only the newest [Schema.MESSAGE_RETENTION] rows. */
    private fun trimMessages() {
        db.writableDatabase.execSQL(
            """
            DELETE FROM ${Schema.Messages.TABLE}
            WHERE ${Schema.Messages.ID} NOT IN (
                SELECT ${Schema.Messages.ID} FROM ${Schema.Messages.TABLE}
                ORDER BY ${Schema.Messages.ID} DESC LIMIT ${Schema.MESSAGE_RETENTION}
            )
            """.trimIndent()
        )
    }

    fun queryMessages(search: String?, statusFilter: String?, limit: Int, offset: Int): JSONArray {
        val where = StringBuilder("1=1")
        val args = mutableListOf<String>()
        if (!search.isNullOrBlank()) {
            where.append(" AND (${Schema.Messages.SENDER} LIKE ? OR ${Schema.Messages.BODY} LIKE ?)")
            args.add("%$search%"); args.add("%$search%")
        }
        if (!statusFilter.isNullOrBlank() && statusFilter != "all") {
            where.append(" AND ${Schema.Messages.WEBHOOK_STATUS}=?")
            args.add(statusFilter)
        }
        val sql = "SELECT * FROM ${Schema.Messages.TABLE} WHERE $where " +
            "ORDER BY ${Schema.Messages.ID} DESC LIMIT $limit OFFSET $offset"
        return rawToJson(sql, args.toTypedArray())
    }

    fun latestMessage(): JSONObject? =
        rawToJson("SELECT * FROM ${Schema.Messages.TABLE} ORDER BY ${Schema.Messages.ID} DESC LIMIT 1", emptyArray())
            .optJSONObject(0)

    fun messageCount(): Int = countOf(Schema.Messages.TABLE)

    // -------------------------------------------------------------------- Rules
    fun upsertRule(json: JSONObject): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(Schema.Rules.NAME, json.optString("name"))
            put(Schema.Rules.SENDER_PATTERN, json.optString("senderPattern"))
            put(Schema.Rules.SENDER_MATCH_TYPE, json.optString("senderMatchType", "contains"))
            put(Schema.Rules.PROCESSOR_TYPE, json.optString("processorType"))
            putOrNull(this, Schema.Rules.TEMPLATE, json, "template")
            putOrNull(this, Schema.Rules.REGEX, json, "regex")
            putOrNull(this, Schema.Rules.CONFIG, json, "config")
            putOrNull(this, Schema.Rules.PAYLOAD_TEMPLATE, json, "payloadTemplate")
            put(Schema.Rules.WEBHOOK_ID, if (json.isNull("webhookId")) null else json.optLong("webhookId"))
            put(Schema.Rules.ENABLED, if (json.optBoolean("enabled", true)) 1 else 0)
            put(Schema.Rules.PRIORITY, json.optInt("priority", 0))
            putOrNull(this, Schema.Rules.SAMPLE_MESSAGES, json, "sampleMessages")
            put(Schema.Rules.UPDATED_AT, now)
        }
        val id = json.optLong("id", 0)
        return if (id > 0) {
            db.writableDatabase.update(Schema.Rules.TABLE, values, "${Schema.Rules.ID}=?", arrayOf(id.toString()))
            id
        } else {
            values.put(Schema.Rules.CREATED_AT, now)
            db.writableDatabase.insert(Schema.Rules.TABLE, null, values)
        }
    }

    fun getRules(): JSONArray =
        rawToJson("SELECT * FROM ${Schema.Rules.TABLE} ORDER BY ${Schema.Rules.PRIORITY} DESC, ${Schema.Rules.ID} ASC", emptyArray())

    /** Enabled rules in evaluation order (highest priority first). */
    fun getEnabledRules(): List<Rule> {
        val out = mutableListOf<Rule>()
        db.readableDatabase.rawQuery(
            "SELECT * FROM ${Schema.Rules.TABLE} WHERE ${Schema.Rules.ENABLED}=1 " +
                "ORDER BY ${Schema.Rules.PRIORITY} DESC, ${Schema.Rules.ID} ASC", null
        ).use { c ->
            while (c.moveToNext()) out.add(c.toRule())
        }
        return out
    }

    fun deleteRule(id: Long) =
        db.writableDatabase.delete(Schema.Rules.TABLE, "${Schema.Rules.ID}=?", arrayOf(id.toString()))

    fun setRuleEnabled(id: Long, enabled: Boolean) {
        val values = ContentValues().apply { put(Schema.Rules.ENABLED, if (enabled) 1 else 0) }
        db.writableDatabase.update(Schema.Rules.TABLE, values, "${Schema.Rules.ID}=?", arrayOf(id.toString()))
    }

    fun incrementMatchCount(id: Long) {
        db.writableDatabase.execSQL(
            "UPDATE ${Schema.Rules.TABLE} SET ${Schema.Rules.MATCH_COUNT}=${Schema.Rules.MATCH_COUNT}+1 WHERE ${Schema.Rules.ID}=?",
            arrayOf(id)
        )
    }

    // ----------------------------------------------------------------- Webhooks
    fun upsertWebhook(json: JSONObject): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(Schema.Webhooks.NAME, json.optString("name"))
            put(Schema.Webhooks.URL, json.optString("url"))
            putOrNull(this, Schema.Webhooks.BEARER_REF, json, "bearerRef")
            putOrNull(this, Schema.Webhooks.HEADERS, json, "headers")
            putOrNull(this, Schema.Webhooks.HMAC_REF, json, "hmacRef")
            put(Schema.Webhooks.ENABLED, if (json.optBoolean("enabled", true)) 1 else 0)
            put(Schema.Webhooks.IS_DEFAULT, if (json.optBoolean("isDefault", false)) 1 else 0)
            put(Schema.Webhooks.UPDATED_AT, now)
        }
        if (json.optBoolean("isDefault", false)) {
            db.writableDatabase.execSQL("UPDATE ${Schema.Webhooks.TABLE} SET ${Schema.Webhooks.IS_DEFAULT}=0")
        }
        val id = json.optLong("id", 0)
        return if (id > 0) {
            db.writableDatabase.update(Schema.Webhooks.TABLE, values, "${Schema.Webhooks.ID}=?", arrayOf(id.toString()))
            id
        } else {
            values.put(Schema.Webhooks.CREATED_AT, now)
            db.writableDatabase.insert(Schema.Webhooks.TABLE, null, values)
        }
    }

    fun getWebhooks(): JSONArray =
        rawToJson("SELECT * FROM ${Schema.Webhooks.TABLE} ORDER BY ${Schema.Webhooks.IS_DEFAULT} DESC, ${Schema.Webhooks.ID} ASC", emptyArray())

    fun getWebhook(id: Long): Webhook? {
        db.readableDatabase.rawQuery("SELECT * FROM ${Schema.Webhooks.TABLE} WHERE ${Schema.Webhooks.ID}=?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) return c.toWebhook() }
        return null
    }

    fun getDefaultWebhook(): Webhook? {
        db.readableDatabase.rawQuery(
            "SELECT * FROM ${Schema.Webhooks.TABLE} WHERE ${Schema.Webhooks.IS_DEFAULT}=1 AND ${Schema.Webhooks.ENABLED}=1 LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) return c.toWebhook() }
        // Fall back to the first enabled webhook.
        db.readableDatabase.rawQuery(
            "SELECT * FROM ${Schema.Webhooks.TABLE} WHERE ${Schema.Webhooks.ENABLED}=1 ORDER BY ${Schema.Webhooks.ID} ASC LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) return c.toWebhook() }
        return null
    }

    /** Every enabled webhook, default first. Used to fan a matched message out to all destinations. */
    fun getEnabledWebhooks(): List<Webhook> {
        val out = mutableListOf<Webhook>()
        db.readableDatabase.rawQuery(
            "SELECT * FROM ${Schema.Webhooks.TABLE} WHERE ${Schema.Webhooks.ENABLED}=1 " +
                "ORDER BY ${Schema.Webhooks.IS_DEFAULT} DESC, ${Schema.Webhooks.ID} ASC", null
        ).use { c -> while (c.moveToNext()) out.add(c.toWebhook()) }
        return out
    }

    fun deleteWebhook(id: Long) =
        db.writableDatabase.delete(Schema.Webhooks.TABLE, "${Schema.Webhooks.ID}=?", arrayOf(id.toString()))

    // -------------------------------------------------------------------- Queue
    fun enqueue(item: QueueItem): Long {
        val values = ContentValues().apply {
            put(Schema.Queue.MESSAGE_ID, item.messageId)
            put(Schema.Queue.WEBHOOK_ID, item.webhookId)
            put(Schema.Queue.WEBHOOK_URL, item.webhookUrl)
            put(Schema.Queue.PAYLOAD, item.payload)
            put(Schema.Queue.IDEMPOTENCY_KEY, item.idempotencyKey)
            put(Schema.Queue.STATUS, "pending")
            put(Schema.Queue.RETRY_COUNT, 0)
            put(Schema.Queue.MAX_RETRIES, item.maxRetries)
            put(Schema.Queue.NEXT_RETRY_AT, 0)
            put(Schema.Queue.CREATED_AT, item.createdAt)
            put(Schema.Queue.UPDATED_AT, item.updatedAt)
        }
        return db.writableDatabase.insert(Schema.Queue.TABLE, null, values)
    }

    /** FIFO claim of due work. Marks rows in_progress so concurrent workers don't double-send. */
    fun claimDue(now: Long, limit: Int): List<QueueItem> {
        val claimed = mutableListOf<QueueItem>()
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            database.rawQuery(
                "SELECT * FROM ${Schema.Queue.TABLE} WHERE ${Schema.Queue.STATUS} IN ('pending','failed') " +
                    "AND ${Schema.Queue.NEXT_RETRY_AT}<=? ORDER BY ${Schema.Queue.ID} ASC LIMIT $limit",
                arrayOf(now.toString())
            ).use { c ->
                while (c.moveToNext()) claimed.add(c.toQueueItem())
            }
            claimed.forEach {
                database.execSQL(
                    "UPDATE ${Schema.Queue.TABLE} SET ${Schema.Queue.STATUS}='in_progress', ${Schema.Queue.UPDATED_AT}=? WHERE ${Schema.Queue.ID}=?",
                    arrayOf(now, it.id)
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return claimed
    }

    fun markSent(id: Long, statusCode: Int) {
        db.writableDatabase.execSQL(
            "UPDATE ${Schema.Queue.TABLE} SET ${Schema.Queue.STATUS}='sent', ${Schema.Queue.LAST_STATUS_CODE}=?, ${Schema.Queue.UPDATED_AT}=? WHERE ${Schema.Queue.ID}=?",
            arrayOf(statusCode, System.currentTimeMillis(), id)
        )
    }

    fun markRetry(id: Long, retryCount: Int, nextRetryAt: Long, error: String?, statusCode: Int) {
        db.writableDatabase.execSQL(
            "UPDATE ${Schema.Queue.TABLE} SET ${Schema.Queue.STATUS}='failed', ${Schema.Queue.RETRY_COUNT}=?, " +
                "${Schema.Queue.NEXT_RETRY_AT}=?, ${Schema.Queue.LAST_ERROR}=?, ${Schema.Queue.LAST_STATUS_CODE}=?, ${Schema.Queue.UPDATED_AT}=? WHERE ${Schema.Queue.ID}=?",
            arrayOf(retryCount, nextRetryAt, error, statusCode, System.currentTimeMillis(), id)
        )
    }

    fun markDead(id: Long, error: String?) {
        db.writableDatabase.execSQL(
            "UPDATE ${Schema.Queue.TABLE} SET ${Schema.Queue.STATUS}='dead', ${Schema.Queue.LAST_ERROR}=?, ${Schema.Queue.UPDATED_AT}=? WHERE ${Schema.Queue.ID}=?",
            arrayOf(error, System.currentTimeMillis(), id)
        )
    }

    fun getQueue(statusFilter: String?): JSONArray {
        return if (statusFilter.isNullOrBlank() || statusFilter == "all") {
            rawToJson("SELECT * FROM ${Schema.Queue.TABLE} ORDER BY ${Schema.Queue.ID} DESC LIMIT 500", emptyArray())
        } else {
            rawToJson("SELECT * FROM ${Schema.Queue.TABLE} WHERE ${Schema.Queue.STATUS}=? ORDER BY ${Schema.Queue.ID} DESC LIMIT 500", arrayOf(statusFilter))
        }
    }

    fun queueStats(): JSONObject {
        val obj = JSONObject()
        db.readableDatabase.rawQuery(
            "SELECT ${Schema.Queue.STATUS}, COUNT(*) c FROM ${Schema.Queue.TABLE} GROUP BY ${Schema.Queue.STATUS}", null
        ).use { c ->
            while (c.moveToNext()) obj.put(c.getString(0), c.getInt(1))
        }
        obj.put("total", countOf(Schema.Queue.TABLE))
        return obj
    }

    fun retryQueueItem(id: Long) {
        db.writableDatabase.execSQL(
            "UPDATE ${Schema.Queue.TABLE} SET ${Schema.Queue.STATUS}='pending', ${Schema.Queue.NEXT_RETRY_AT}=0, ${Schema.Queue.RETRY_COUNT}=0 WHERE ${Schema.Queue.ID}=?",
            arrayOf(id)
        )
    }

    fun retryAllDead() {
        db.writableDatabase.execSQL(
            "UPDATE ${Schema.Queue.TABLE} SET ${Schema.Queue.STATUS}='pending', ${Schema.Queue.NEXT_RETRY_AT}=0, ${Schema.Queue.RETRY_COUNT}=0 WHERE ${Schema.Queue.STATUS}='dead'"
        )
    }

    fun deleteQueueItem(id: Long) =
        db.writableDatabase.delete(Schema.Queue.TABLE, "${Schema.Queue.ID}=?", arrayOf(id.toString()))

    /** Reclaim items stuck in_progress from a worker that was killed mid-send. */
    fun recoverStaleInProgress(olderThan: Long) {
        db.writableDatabase.execSQL(
            "UPDATE ${Schema.Queue.TABLE} SET ${Schema.Queue.STATUS}='pending' " +
                "WHERE ${Schema.Queue.STATUS}='in_progress' AND ${Schema.Queue.UPDATED_AT}<?",
            arrayOf(olderThan)
        )
    }

    fun hasDueWork(now: Long): Boolean {
        db.readableDatabase.rawQuery(
            "SELECT 1 FROM ${Schema.Queue.TABLE} WHERE ${Schema.Queue.STATUS} IN ('pending','failed') AND ${Schema.Queue.NEXT_RETRY_AT}<=? LIMIT 1",
            arrayOf(now.toString())
        ).use { c -> return c.moveToFirst() }
    }

    // ----------------------------------------------------------------- Settings
    fun getSetting(key: String, default: String? = null): String? {
        db.readableDatabase.rawQuery(
            "SELECT ${Schema.Settings.VALUE} FROM ${Schema.Settings.TABLE} WHERE ${Schema.Settings.KEY}=?", arrayOf(key)
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else default }
    }

    fun setSetting(key: String, value: String?) {
        val values = ContentValues().apply {
            put(Schema.Settings.KEY, key)
            put(Schema.Settings.VALUE, value)
        }
        db.writableDatabase.insertWithOnConflict(
            Schema.Settings.TABLE, null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getAllSettings(): JSONObject {
        val obj = JSONObject()
        db.readableDatabase.rawQuery("SELECT * FROM ${Schema.Settings.TABLE}", null).use { c ->
            while (c.moveToNext()) obj.put(c.getString(0), c.getString(1))
        }
        return obj
    }

    // -------------------------------------------------------------- SIM history
    fun insertSimEvent(
        event: String, slot: Int, subscriptionId: Int, carrier: String?, iccid: String?,
        phoneNumber: String?, oldSnapshot: String?, newSnapshot: String?
    ): Long {
        val values = ContentValues().apply {
            put(Schema.SimHistory.EVENT, event)
            put(Schema.SimHistory.SLOT, slot)
            put(Schema.SimHistory.SUBSCRIPTION_ID, subscriptionId)
            put(Schema.SimHistory.CARRIER, carrier)
            put(Schema.SimHistory.ICCID, iccid)
            put(Schema.SimHistory.PHONE_NUMBER, phoneNumber)
            put(Schema.SimHistory.OLD_SNAPSHOT, oldSnapshot)
            put(Schema.SimHistory.NEW_SNAPSHOT, newSnapshot)
            put(Schema.SimHistory.TIMESTAMP, System.currentTimeMillis())
        }
        return db.writableDatabase.insert(Schema.SimHistory.TABLE, null, values)
    }

    fun getSimHistory(): JSONArray =
        rawToJson("SELECT * FROM ${Schema.SimHistory.TABLE} ORDER BY ${Schema.SimHistory.ID} DESC LIMIT 200", emptyArray())

    // --------------------------------------------------------------- SIM config
    /** Saved config for a physical slot, or null when the slot has never been set up. */
    fun getSimConfigForSlot(slot: Int): SimConfigRow? {
        db.readableDatabase.rawQuery(
            "SELECT * FROM ${Schema.SimConfig.TABLE} WHERE ${Schema.SimConfig.SLOT}=?", arrayOf(slot.toString())
        ).use { c -> if (c.moveToFirst()) return c.toSimConfigRow() }
        return null
    }

    fun getSimConfigs(): JSONArray =
        rawToJson("SELECT * FROM ${Schema.SimConfig.TABLE} ORDER BY ${Schema.SimConfig.SLOT} ASC", emptyArray())

    /** Upsert the config for one slot. Expects camelCase JSON from the bridge. */
    fun upsertSimConfig(json: JSONObject) {
        val slot = json.optInt("slot", -1)
        if (slot < 0) return
        // Normalise services to a JSON array string, accepting either an array or absent.
        val services = when (val s = json.opt("services")) {
            is JSONArray -> s.toString()
            is String -> s.ifBlank { "[]" }
            else -> "[]"
        }
        val values = ContentValues().apply {
            put(Schema.SimConfig.SLOT, slot)
            put(Schema.SimConfig.SUBSCRIPTION_ID, json.optInt("subscriptionId", -1))
            putOrNull(this, Schema.SimConfig.ICCID, json, "iccid")
            putOrNull(this, Schema.SimConfig.PHONE_NUMBER, json, "phoneNumber")
            putOrNull(this, Schema.SimConfig.CARRIER, json, "carrier")
            put(Schema.SimConfig.RECORD_ENABLED, if (json.optBoolean("recordEnabled", false)) 1 else 0)
            put(Schema.SimConfig.SERVICES, services)
            put(Schema.SimConfig.UPDATED_AT, System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict(
            Schema.SimConfig.TABLE, null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ----------------------------------------------------------------- Services
    fun getServices(): JSONArray =
        rawToJson("SELECT * FROM ${Schema.Services.TABLE} ORDER BY ${Schema.Services.NAME} ASC", emptyArray())

    /** Add a service by name (idempotent — duplicates are ignored). Returns the row id or -1. */
    fun addService(name: String): Long {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return -1
        val values = ContentValues().apply {
            put(Schema.Services.NAME, trimmed)
            put(Schema.Services.ENABLED, 1)
            put(Schema.Services.CREATED_AT, System.currentTimeMillis())
        }
        return db.writableDatabase.insertWithOnConflict(
            Schema.Services.TABLE, null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun setServiceEnabled(id: Long, enabled: Boolean) {
        val values = ContentValues().apply { put(Schema.Services.ENABLED, if (enabled) 1 else 0) }
        db.writableDatabase.update(Schema.Services.TABLE, values, "${Schema.Services.ID}=?", arrayOf(id.toString()))
    }

    fun deleteService(id: Long) =
        db.writableDatabase.delete(Schema.Services.TABLE, "${Schema.Services.ID}=?", arrayOf(id.toString()))

    /**
     * One-time first-run seed: a starter service catalog and ready-made extraction rules, so the
     * app works out of the box. Idempotent and guarded by a version flag; rules are only seeded
     * when the user has none, so it never clobbers their own work.
     */
    fun seedDefaultsIfNeeded() {
        if (getSetting(Schema.Settings.SEED_FLAG) == DefaultSeed.VERSION) return
        // Services are additive/idempotent.
        DefaultSeed.services.forEach { addService(it) }
        // (Re)apply the managed default rules *by name*: delete the same-named rule first, then
        // re-insert the current version. This lets an app update ship a fixed pattern that actually
        // replaces the one already on the device, without duplicating rules or touching any rule the
        // operator created themselves (those have different names and are left untouched).
        DefaultSeed.rules().forEach { rule ->
            deleteRuleByName(rule.optString("name"))
            upsertRule(rule)
        }
        setSetting(Schema.Settings.SEED_FLAG, DefaultSeed.VERSION)
        log("system", "info", "Seeded/refreshed default services and rules (${DefaultSeed.VERSION})")
    }

    /** Delete any rule matching this exact name (used to refresh managed default rules on update). */
    fun deleteRuleByName(name: String) {
        if (name.isBlank()) return
        db.writableDatabase.delete(Schema.Rules.TABLE, "${Schema.Rules.NAME}=?", arrayOf(name))
    }

    // -------------------------------------------------------------- Diagnostics
    fun log(type: String, level: String, message: String) {
        val values = ContentValues().apply {
            put(Schema.DiagnosticsLog.TYPE, type)
            put(Schema.DiagnosticsLog.LEVEL, level)
            put(Schema.DiagnosticsLog.MESSAGE, message)
            put(Schema.DiagnosticsLog.TIMESTAMP, System.currentTimeMillis())
        }
        db.writableDatabase.insert(Schema.DiagnosticsLog.TABLE, null, values)
        // Keep the log bounded.
        db.writableDatabase.execSQL(
            "DELETE FROM ${Schema.DiagnosticsLog.TABLE} WHERE ${Schema.DiagnosticsLog.ID} NOT IN " +
                "(SELECT ${Schema.DiagnosticsLog.ID} FROM ${Schema.DiagnosticsLog.TABLE} ORDER BY ${Schema.DiagnosticsLog.ID} DESC LIMIT 500)"
        )
    }

    fun getDiagnosticsLog(limit: Int): JSONArray =
        rawToJson("SELECT * FROM ${Schema.DiagnosticsLog.TABLE} ORDER BY ${Schema.DiagnosticsLog.ID} DESC LIMIT $limit", emptyArray())

    fun countLogs(type: String, level: String): Int {
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${Schema.DiagnosticsLog.TABLE} WHERE ${Schema.DiagnosticsLog.TYPE}=? AND ${Schema.DiagnosticsLog.LEVEL}=?",
            arrayOf(type, level)
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun lastLogOf(type: String, level: String): JSONObject? =
        rawToJson(
            "SELECT * FROM ${Schema.DiagnosticsLog.TABLE} WHERE ${Schema.DiagnosticsLog.TYPE}=? AND ${Schema.DiagnosticsLog.LEVEL}=? ORDER BY ${Schema.DiagnosticsLog.ID} DESC LIMIT 1",
            arrayOf(type, level)
        ).optJSONObject(0)

    // ------------------------------------------------------------------ Helpers
    private fun countOf(table: String): Int {
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun putOrNull(cv: ContentValues, column: String, json: JSONObject, key: String) {
        if (json.isNull(key) || !json.has(key)) cv.putNull(column) else cv.put(column, json.optString(key))
    }

    /** Generic cursor → JSONArray projection so the bridge can stay schema-agnostic. */
    private fun rawToJson(sql: String, args: Array<String>): JSONArray {
        val arr = JSONArray()
        db.readableDatabase.rawQuery(sql, args).use { c ->
            val cols = c.columnNames
            while (c.moveToNext()) {
                val obj = JSONObject()
                for (i in cols.indices) {
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> obj.put(cols[i], JSONObject.NULL)
                        Cursor.FIELD_TYPE_INTEGER -> obj.put(cols[i], c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> obj.put(cols[i], c.getDouble(i))
                        else -> obj.put(cols[i], c.getString(i))
                    }
                }
                arr.put(obj)
            }
        }
        return arr
    }

    private fun Cursor.toRule() = Rule(
        id = getLong(getColumnIndexOrThrow(Schema.Rules.ID)),
        name = getStringOrEmpty(Schema.Rules.NAME),
        senderPattern = getStringOrEmpty(Schema.Rules.SENDER_PATTERN),
        senderMatchType = getStringOrEmpty(Schema.Rules.SENDER_MATCH_TYPE),
        processorType = getStringOrEmpty(Schema.Rules.PROCESSOR_TYPE),
        template = getStringOrNull(Schema.Rules.TEMPLATE),
        regex = getStringOrNull(Schema.Rules.REGEX),
        config = getStringOrNull(Schema.Rules.CONFIG),
        webhookId = getLongOrNull(Schema.Rules.WEBHOOK_ID),
        payloadTemplate = getStringOrNull(Schema.Rules.PAYLOAD_TEMPLATE),
        enabled = getInt(getColumnIndexOrThrow(Schema.Rules.ENABLED)) == 1,
        priority = getInt(getColumnIndexOrThrow(Schema.Rules.PRIORITY)),
        sampleMessages = getStringOrNull(Schema.Rules.SAMPLE_MESSAGES),
        matchCount = getInt(getColumnIndexOrThrow(Schema.Rules.MATCH_COUNT))
    )

    private fun Cursor.toWebhook() = Webhook(
        id = getLong(getColumnIndexOrThrow(Schema.Webhooks.ID)),
        name = getStringOrEmpty(Schema.Webhooks.NAME),
        url = getStringOrEmpty(Schema.Webhooks.URL),
        bearerRef = getStringOrNull(Schema.Webhooks.BEARER_REF),
        headers = getStringOrNull(Schema.Webhooks.HEADERS),
        hmacRef = getStringOrNull(Schema.Webhooks.HMAC_REF),
        enabled = getInt(getColumnIndexOrThrow(Schema.Webhooks.ENABLED)) == 1,
        isDefault = getInt(getColumnIndexOrThrow(Schema.Webhooks.IS_DEFAULT)) == 1
    )

    private fun Cursor.toQueueItem() = QueueItem(
        id = getLong(getColumnIndexOrThrow(Schema.Queue.ID)),
        messageId = getLongOrNull(Schema.Queue.MESSAGE_ID),
        webhookId = getLongOrNull(Schema.Queue.WEBHOOK_ID),
        webhookUrl = getStringOrEmpty(Schema.Queue.WEBHOOK_URL),
        payload = getStringOrEmpty(Schema.Queue.PAYLOAD),
        idempotencyKey = getStringOrEmpty(Schema.Queue.IDEMPOTENCY_KEY),
        status = getStringOrEmpty(Schema.Queue.STATUS),
        retryCount = getInt(getColumnIndexOrThrow(Schema.Queue.RETRY_COUNT)),
        maxRetries = getInt(getColumnIndexOrThrow(Schema.Queue.MAX_RETRIES)),
        nextRetryAt = getLong(getColumnIndexOrThrow(Schema.Queue.NEXT_RETRY_AT)),
        lastError = getStringOrNull(Schema.Queue.LAST_ERROR),
        lastStatusCode = getInt(getColumnIndexOrThrow(Schema.Queue.LAST_STATUS_CODE))
    )

    private fun Cursor.toSimConfigRow(): SimConfigRow {
        val servicesJson = getStringOrNull(Schema.SimConfig.SERVICES) ?: "[]"
        val services = runCatching {
            val arr = JSONArray(servicesJson)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
        return SimConfigRow(
            slot = getInt(getColumnIndexOrThrow(Schema.SimConfig.SLOT)),
            subscriptionId = getInt(getColumnIndexOrThrow(Schema.SimConfig.SUBSCRIPTION_ID)),
            iccid = getStringOrNull(Schema.SimConfig.ICCID),
            phoneNumber = getStringOrNull(Schema.SimConfig.PHONE_NUMBER),
            carrier = getStringOrNull(Schema.SimConfig.CARRIER),
            recordEnabled = getInt(getColumnIndexOrThrow(Schema.SimConfig.RECORD_ENABLED)) == 1,
            services = services
        )
    }

    private fun Cursor.getStringOrEmpty(col: String) = getString(getColumnIndexOrThrow(col)) ?: ""
    private fun Cursor.getStringOrNull(col: String): String? {
        val i = getColumnIndexOrThrow(col); return if (isNull(i)) null else getString(i)
    }
    private fun Cursor.getLongOrNull(col: String): Long? {
        val i = getColumnIndexOrThrow(col); return if (isNull(i)) null else getLong(i)
    }

    companion object {
        @Volatile
        private var instance: Repository? = null

        fun get(context: Context): Repository =
            instance ?: synchronized(this) {
                instance ?: Repository(context.applicationContext).also { instance = it }
            }
    }
}
