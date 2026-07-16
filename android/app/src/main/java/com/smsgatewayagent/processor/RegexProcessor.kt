package com.smsgatewayagent.processor

import com.smsgatewayagent.model.Rule
import com.smsgatewayagent.model.SmsMessage
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Runs a user-supplied regular expression against the SMS body. Named capture groups
 * (`(?<amount>...)`) become extracted fields; numbered groups are exposed as `group1`, `group2`…
 */
class RegexProcessor : Processor {
    override val type = "regex"
    override val displayName = "Regex"

    override fun process(message: SmsMessage, rule: Rule, context: ProcessorContext): ProcessorResult {
        val regex = rule.regex
        if (regex.isNullOrBlank()) return ProcessorResult.failure("Regex is empty")

        return try {
            val pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
            val matcher = pattern.matcher(message.body)
            if (!matcher.find()) {
                ProcessorResult.noMatch("Body did not match regex")
            } else {
                val data = JSONObject()
                for (name in namedGroups(regex)) {
                    try {
                        matcher.group(name)?.let { data.put(name, it.trim()) }
                    } catch (_: Exception) { /* optional group */ }
                }
                for (i in 1..matcher.groupCount()) {
                    matcher.group(i)?.let { data.put("group$i", it.trim()) }
                }
                data.put("sender", message.sender)
                data.put("body", message.body)
                ProcessorResult(matched = true, data = data)
            }
        } catch (e: Exception) {
            ProcessorResult.failure("Regex error: ${e.message}")
        }
    }

    private fun namedGroups(regex: String): List<String> {
        val m = Pattern.compile("""\(\?<([A-Za-z][A-Za-z0-9]*)>""").matcher(regex)
        val out = mutableListOf<String>()
        while (m.find()) out.add(m.group(1)!!)
        return out
    }
}
