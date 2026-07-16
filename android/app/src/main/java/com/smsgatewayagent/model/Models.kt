package com.smsgatewayagent.model

import org.json.JSONObject

/** Captured inbound SMS plus everything we know about its origin. */
data class SmsMessage(
    val id: Long = 0,
    val hash: String,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val simSlot: Int,
    val subscriptionId: Int,
    val deviceId: String,
    val phoneNumber: String?,
    val matchedRuleId: Long? = null,
    val processorType: String? = null,
    val parsedData: String? = null,
    val webhookStatus: String = "none",
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/** A user-defined rule: sender match + processor + destination webhook. */
data class Rule(
    val id: Long = 0,
    val name: String,
    val senderPattern: String,
    val senderMatchType: String = "contains",
    val processorType: String,
    val template: String? = null,
    val regex: String? = null,
    val config: String? = null,
    val webhookId: Long? = null,
    val payloadTemplate: String? = null,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val sampleMessages: String? = null,
    val matchCount: Int = 0
)

/** A webhook destination. Secrets live in EncryptedSharedPreferences, referenced by *_ref. */
data class Webhook(
    val id: Long = 0,
    val name: String,
    val url: String,
    val bearerRef: String? = null,
    val headers: String? = null,
    val hmacRef: String? = null,
    val enabled: Boolean = true,
    val isDefault: Boolean = false
)

/** A persistent delivery job. The queue is FIFO with exponential backoff + dead-letter. */
data class QueueItem(
    val id: Long = 0,
    val messageId: Long?,
    val webhookId: Long?,
    val webhookUrl: String,
    val payload: String,
    val idempotencyKey: String,
    val status: String = "pending",
    val retryCount: Int = 0,
    val maxRetries: Int = 10,
    val nextRetryAt: Long = 0,
    val lastError: String? = null,
    val lastStatusCode: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Saved per-SIM capture configuration. Anchored on [slot]; [phoneNumber] is the operator-facing
 * identity. [recordEnabled] gates whether SMS from this SIM is captured at all, and [services]
 * are the payment-channel tags stamped onto every forwarded payload.
 */
data class SimConfigRow(
    val slot: Int,
    val subscriptionId: Int,
    val iccid: String?,
    val phoneNumber: String?,
    val carrier: String?,
    val recordEnabled: Boolean,
    val services: List<String>
)

/** Live SIM snapshot for one slot. */
data class SimInfo(
    val slot: Int,
    val subscriptionId: Int,
    val carrier: String?,
    val iccid: String?,
    val phoneNumber: String?,
    val displayName: String?,
    val countryIso: String?,
    /**
     * Where [phoneNumber] came from, so callers can tell *why* it may be blank:
     *  - "sim"                    → read from the SIM/carrier
     *  - "manual"                 → user-entered override
     *  - "unavailable_carrier"    → SIM present & permitted, but carrier never provisioned the MSISDN
     *  - "unavailable_permission" → the number-reading permission (READ_PHONE_NUMBERS/READ_SMS) is denied
     */
    val phoneNumberSource: String = "unavailable_carrier"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("slot", slot)
        put("subscriptionId", subscriptionId)
        put("carrier", carrier ?: JSONObject.NULL)
        put("iccid", iccid ?: JSONObject.NULL)
        put("phoneNumber", phoneNumber ?: JSONObject.NULL)
        put("phoneNumberSource", phoneNumberSource)
        put("displayName", displayName ?: JSONObject.NULL)
        put("countryIso", countryIso ?: JSONObject.NULL)
    }
}
