package com.smsgatewayagent.data

/**
 * Single source of truth for the SQLite schema.
 *
 * The native layer owns the database; the React Native UI talks to it exclusively
 * through [com.smsgatewayagent.bridge.SmsGatewayModule] so there is never more than
 * one writer process or two competing SQLite stacks.
 */
object Schema {
    const val DB_NAME = "sms_gateway_agent.db"
    // v2: adds sim_config + services (per-SIM record flag + dynamic service assignment).
    const val DB_VERSION = 2

    /** Hard cap on retained inbox rows. Oldest beyond this are trimmed automatically. */
    const val MESSAGE_RETENTION = 1000

    object Messages {
        const val TABLE = "messages"
        const val ID = "id"
        const val HASH = "sms_hash"            // dedupe key (sender|body|timestamp)
        const val SENDER = "sender"
        const val BODY = "body"
        const val TIMESTAMP = "timestamp"      // SMS receive time (epoch ms)
        const val SIM_SLOT = "sim_slot"
        const val SUBSCRIPTION_ID = "subscription_id"
        const val DEVICE_ID = "device_id"
        const val PHONE_NUMBER = "phone_number"
        const val MATCHED_RULE_ID = "matched_rule_id"
        const val PROCESSOR_TYPE = "processor_type"
        const val PARSED_DATA = "parsed_data"  // JSON
        const val WEBHOOK_STATUS = "webhook_status"
        const val RETRY_COUNT = "retry_count"
        const val CREATED_AT = "created_at"
    }

    object Rules {
        const val TABLE = "rules"
        const val ID = "id"
        const val NAME = "name"
        const val SENDER_PATTERN = "sender_pattern"
        const val SENDER_MATCH_TYPE = "sender_match_type" // any|exact|contains|regex
        const val PROCESSOR_TYPE = "processor_type"       // raw|template|regex|json|javascript
        const val TEMPLATE = "template"
        const val REGEX = "regex"
        const val CONFIG = "config"                       // JSON processor config
        const val WEBHOOK_ID = "webhook_id"
        const val PAYLOAD_TEMPLATE = "payload_template"
        const val ENABLED = "enabled"
        const val PRIORITY = "priority"
        const val SAMPLE_MESSAGES = "sample_messages"     // JSON array
        const val MATCH_COUNT = "match_count"
        const val CREATED_AT = "created_at"
        const val UPDATED_AT = "updated_at"
    }

    object Webhooks {
        const val TABLE = "webhooks"
        const val ID = "id"
        const val NAME = "name"
        const val URL = "url"
        const val BEARER_REF = "bearer_ref"   // secure-prefs key, not the secret itself
        const val HEADERS = "headers"          // JSON object
        const val HMAC_REF = "hmac_ref"        // secure-prefs key
        const val ENABLED = "enabled"
        const val IS_DEFAULT = "is_default"
        const val CREATED_AT = "created_at"
        const val UPDATED_AT = "updated_at"
    }

    object Queue {
        const val TABLE = "queue"
        const val ID = "id"
        const val MESSAGE_ID = "message_id"
        const val WEBHOOK_ID = "webhook_id"
        const val WEBHOOK_URL = "webhook_url"
        const val PAYLOAD = "payload"          // JSON body, ready to send
        const val IDEMPOTENCY_KEY = "idempotency_key"
        const val STATUS = "status"            // pending|in_progress|sent|failed|dead
        const val RETRY_COUNT = "retry_count"
        const val MAX_RETRIES = "max_retries"
        const val NEXT_RETRY_AT = "next_retry_at"
        const val LAST_ERROR = "last_error"
        const val LAST_STATUS_CODE = "last_status_code"
        const val CREATED_AT = "created_at"
        const val UPDATED_AT = "updated_at"
    }

    object Settings {
        const val TABLE = "settings"
        const val KEY = "key"
        const val VALUE = "value"
        const val SEED_FLAG = "seeded_defaults" // stores the DefaultSeed.VERSION already applied
    }

    object SimHistory {
        const val TABLE = "sim_history"
        const val ID = "id"
        const val EVENT = "event"              // inserted|removed|changed|swapped|snapshot
        const val SLOT = "slot"
        const val SUBSCRIPTION_ID = "subscription_id"
        const val CARRIER = "carrier"
        const val ICCID = "iccid"
        const val PHONE_NUMBER = "phone_number"
        const val OLD_SNAPSHOT = "old_snapshot"
        const val NEW_SNAPSHOT = "new_snapshot"
        const val TIMESTAMP = "timestamp"
    }

    object DiagnosticsLog {
        const val TABLE = "diagnostics_log"
        const val ID = "id"
        const val TYPE = "type"                 // processor|webhook|queue|sim|system
        const val LEVEL = "level"               // info|warn|error
        const val MESSAGE = "message"
        const val TIMESTAMP = "timestamp"
    }

    /**
     * Per-SIM capture configuration. Anchored on the physical [SLOT] (the only identifier the
     * inbound SMS broadcast reliably carries) but the operator-facing identity is [PHONE_NUMBER].
     * A SIM with no row here — or with [RECORD_ENABLED]=0 — is ignored entirely (never stored or
     * forwarded), so a personal SIM in an unused slot leaks nothing.
     */
    object SimConfig {
        const val TABLE = "sim_config"
        const val SLOT = "slot"                 // PK, physical slot index — the match anchor
        const val SUBSCRIPTION_ID = "subscription_id"
        const val ICCID = "iccid"
        const val PHONE_NUMBER = "phone_number" // operator-assigned; shown in UI + sent to server
        const val CARRIER = "carrier"
        const val RECORD_ENABLED = "record_enabled" // 0/1 capture gate
        const val SERVICES = "services"         // JSON array of service names, e.g. ["bkash"]
        const val UPDATED_AT = "updated_at"
    }

    /** Dynamic catalog of payment services (bkash, nagad, …). Names are user-defined. */
    object Services {
        const val TABLE = "services"
        const val ID = "id"
        const val NAME = "name"                 // UNIQUE, e.g. "bkash"
        const val ENABLED = "enabled"
        const val CREATED_AT = "created_at"
    }

    val CREATE_STATEMENTS: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS ${Messages.TABLE} (
            ${Messages.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
            ${Messages.HASH} TEXT UNIQUE,
            ${Messages.SENDER} TEXT,
            ${Messages.BODY} TEXT,
            ${Messages.TIMESTAMP} INTEGER,
            ${Messages.SIM_SLOT} INTEGER,
            ${Messages.SUBSCRIPTION_ID} INTEGER,
            ${Messages.DEVICE_ID} TEXT,
            ${Messages.PHONE_NUMBER} TEXT,
            ${Messages.MATCHED_RULE_ID} INTEGER,
            ${Messages.PROCESSOR_TYPE} TEXT,
            ${Messages.PARSED_DATA} TEXT,
            ${Messages.WEBHOOK_STATUS} TEXT DEFAULT 'none',
            ${Messages.RETRY_COUNT} INTEGER DEFAULT 0,
            ${Messages.CREATED_AT} INTEGER
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_messages_ts ON ${Messages.TABLE}(${Messages.TIMESTAMP} DESC)",
        """
        CREATE TABLE IF NOT EXISTS ${Rules.TABLE} (
            ${Rules.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
            ${Rules.NAME} TEXT,
            ${Rules.SENDER_PATTERN} TEXT,
            ${Rules.SENDER_MATCH_TYPE} TEXT DEFAULT 'contains',
            ${Rules.PROCESSOR_TYPE} TEXT,
            ${Rules.TEMPLATE} TEXT,
            ${Rules.REGEX} TEXT,
            ${Rules.CONFIG} TEXT,
            ${Rules.WEBHOOK_ID} INTEGER,
            ${Rules.PAYLOAD_TEMPLATE} TEXT,
            ${Rules.ENABLED} INTEGER DEFAULT 1,
            ${Rules.PRIORITY} INTEGER DEFAULT 0,
            ${Rules.SAMPLE_MESSAGES} TEXT,
            ${Rules.MATCH_COUNT} INTEGER DEFAULT 0,
            ${Rules.CREATED_AT} INTEGER,
            ${Rules.UPDATED_AT} INTEGER
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${Webhooks.TABLE} (
            ${Webhooks.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
            ${Webhooks.NAME} TEXT,
            ${Webhooks.URL} TEXT,
            ${Webhooks.BEARER_REF} TEXT,
            ${Webhooks.HEADERS} TEXT,
            ${Webhooks.HMAC_REF} TEXT,
            ${Webhooks.ENABLED} INTEGER DEFAULT 1,
            ${Webhooks.IS_DEFAULT} INTEGER DEFAULT 0,
            ${Webhooks.CREATED_AT} INTEGER,
            ${Webhooks.UPDATED_AT} INTEGER
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${Queue.TABLE} (
            ${Queue.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
            ${Queue.MESSAGE_ID} INTEGER,
            ${Queue.WEBHOOK_ID} INTEGER,
            ${Queue.WEBHOOK_URL} TEXT,
            ${Queue.PAYLOAD} TEXT,
            ${Queue.IDEMPOTENCY_KEY} TEXT,
            ${Queue.STATUS} TEXT DEFAULT 'pending',
            ${Queue.RETRY_COUNT} INTEGER DEFAULT 0,
            ${Queue.MAX_RETRIES} INTEGER DEFAULT 10,
            ${Queue.NEXT_RETRY_AT} INTEGER DEFAULT 0,
            ${Queue.LAST_ERROR} TEXT,
            ${Queue.LAST_STATUS_CODE} INTEGER DEFAULT 0,
            ${Queue.CREATED_AT} INTEGER,
            ${Queue.UPDATED_AT} INTEGER
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_queue_status ON ${Queue.TABLE}(${Queue.STATUS}, ${Queue.NEXT_RETRY_AT})",
        """
        CREATE TABLE IF NOT EXISTS ${Settings.TABLE} (
            ${Settings.KEY} TEXT PRIMARY KEY,
            ${Settings.VALUE} TEXT
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${SimHistory.TABLE} (
            ${SimHistory.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
            ${SimHistory.EVENT} TEXT,
            ${SimHistory.SLOT} INTEGER,
            ${SimHistory.SUBSCRIPTION_ID} INTEGER,
            ${SimHistory.CARRIER} TEXT,
            ${SimHistory.ICCID} TEXT,
            ${SimHistory.PHONE_NUMBER} TEXT,
            ${SimHistory.OLD_SNAPSHOT} TEXT,
            ${SimHistory.NEW_SNAPSHOT} TEXT,
            ${SimHistory.TIMESTAMP} INTEGER
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${DiagnosticsLog.TABLE} (
            ${DiagnosticsLog.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
            ${DiagnosticsLog.TYPE} TEXT,
            ${DiagnosticsLog.LEVEL} TEXT,
            ${DiagnosticsLog.MESSAGE} TEXT,
            ${DiagnosticsLog.TIMESTAMP} INTEGER
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${SimConfig.TABLE} (
            ${SimConfig.SLOT} INTEGER PRIMARY KEY,
            ${SimConfig.SUBSCRIPTION_ID} INTEGER,
            ${SimConfig.ICCID} TEXT,
            ${SimConfig.PHONE_NUMBER} TEXT,
            ${SimConfig.CARRIER} TEXT,
            ${SimConfig.RECORD_ENABLED} INTEGER DEFAULT 0,
            ${SimConfig.SERVICES} TEXT DEFAULT '[]',
            ${SimConfig.UPDATED_AT} INTEGER
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${Services.TABLE} (
            ${Services.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
            ${Services.NAME} TEXT UNIQUE,
            ${Services.ENABLED} INTEGER DEFAULT 1,
            ${Services.CREATED_AT} INTEGER
        )
        """.trimIndent()
    )
}
