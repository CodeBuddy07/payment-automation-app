package com.smsgatewayagent.processor

import com.smsgatewayagent.model.Rule
import com.smsgatewayagent.model.SmsMessage
import org.json.JSONObject

/** Passes the message through unchanged. Always matches; useful for raw forwarding. */
class RawSmsProcessor : Processor {
    override val type = "raw"
    override val displayName = "Raw SMS"

    override fun process(message: SmsMessage, rule: Rule, context: ProcessorContext): ProcessorResult {
        val data = JSONObject().apply {
            put("sender", message.sender)
            put("body", message.body)
            put("timestamp", message.timestamp)
        }
        return ProcessorResult(matched = true, data = data)
    }
}
