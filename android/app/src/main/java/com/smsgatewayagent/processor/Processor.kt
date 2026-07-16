package com.smsgatewayagent.processor

import com.smsgatewayagent.model.Rule
import com.smsgatewayagent.model.SmsMessage
import org.json.JSONObject

/**
 * The structured output of a processor run.
 *
 * @property matched     whether the processor considered the message relevant
 * @property data        extracted key/value pairs available to the payload template
 * @property errors      non-fatal problems encountered while processing
 * @property metadata    processor-specific diagnostics (timings, engine info, etc.)
 */
data class ProcessorResult(
    val matched: Boolean,
    val data: JSONObject = JSONObject(),
    val errors: List<String> = emptyList(),
    val metadata: JSONObject = JSONObject()
) {
    companion object {
        fun noMatch(reason: String? = null) =
            ProcessorResult(false, errors = reason?.let { listOf(it) } ?: emptyList())

        fun failure(error: String) = ProcessorResult(false, errors = listOf(error))
    }
}

/** Services made available to every processor without coupling it to the DB or Android. */
class ProcessorContext(
    val templateEngine: TemplateEngine = TemplateEngine
)

/**
 * The single extension point of the system. New processors (Payment, AI, HTTP, custom
 * user processors…) implement this interface and register themselves in [ProcessorRegistry]
 * — no other part of the app needs to change.
 */
interface Processor {
    /** Stable identifier persisted on rules, e.g. "raw", "template", "regex", "json", "javascript". */
    val type: String

    /** Human-readable name shown in the Processor Management screen. */
    val displayName: String

    fun process(message: SmsMessage, rule: Rule, context: ProcessorContext): ProcessorResult
}
