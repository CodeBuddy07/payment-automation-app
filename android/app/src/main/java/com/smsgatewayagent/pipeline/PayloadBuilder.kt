package com.smsgatewayagent.pipeline

import com.smsgatewayagent.model.SmsMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the outgoing webhook body.
 *
 * If the rule defines a `payloadTemplate`, it is rendered with `{{variable}}` substitution where
 * variables come from the processor output plus message/device/SIM context. Otherwise a standard
 * envelope is sent.
 */
object PayloadBuilder {

    /** Standard envelope used when a rule has no custom payload template. */
    fun defaultPayload(
        message: SmsMessage,
        parsedData: JSONObject,
        deviceId: String,
        simInfo: JSONObject?,
        station: JSONObject?,
        services: List<String>
    ): JSONObject = JSONObject().apply {
        // `simInfo` carries the SIM identity (number/carrier/slot) plus the services this SIM is
        // assigned to, so the server knows which payment channel the SMS belongs to.
        val enrichedSim = (simInfo ?: JSONObject()).apply { put("services", JSONArray(services)) }
        put("event", "payment_sms")
        put("deviceId", deviceId)
        put("station", station ?: JSONObject.NULL)
        put("sender", message.sender)
        put("body", message.body)
        put("rawSms", message.body)
        put("timestamp", message.timestamp)
        put("parsedData", parsedData)
        put("simInfo", enrichedSim)
    }

    /** Variable map exposed to `{{...}}` placeholders in custom payload templates. */
    fun variables(
        message: SmsMessage,
        parsedData: JSONObject,
        deviceId: String,
        simInfo: JSONObject?,
        stationName: String,
        services: List<String>
    ): Map<String, String> {
        val vars = HashMap<String, String>()
        vars["sender"] = message.sender
        vars["body"] = message.body
        vars["rawSms"] = message.body
        vars["timestamp"] = message.timestamp.toString()
        vars["deviceId"] = deviceId
        vars["stationName"] = stationName
        vars["services"] = services.joinToString(",")
        vars["service"] = services.firstOrNull() ?: ""
        vars["simSlot"] = message.simSlot.toString()
        vars["subscriptionId"] = message.subscriptionId.toString()
        vars["phoneNumber"] = simInfo?.optString("phoneNumber")?.takeIf { it.isNotBlank() }
            ?: message.phoneNumber ?: ""
        // Processor-extracted fields take precedence and add their own keys.
        val keys = parsedData.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            vars[k] = parsedData.opt(k)?.toString() ?: ""
        }
        return vars
    }

    /** Render a `{{var}}` template string. Unknown variables resolve to empty. */
    fun render(template: String, vars: Map<String, String>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            if (i + 1 < template.length && template[i] == '{' && template[i + 1] == '{') {
                val end = template.indexOf("}}", i + 2)
                if (end >= 0) {
                    val key = template.substring(i + 2, end).trim()
                    sb.append(vars[key] ?: "")
                    i = end + 2
                    continue
                }
            }
            sb.append(template[i]); i++
        }
        return sb.toString()
    }
}
