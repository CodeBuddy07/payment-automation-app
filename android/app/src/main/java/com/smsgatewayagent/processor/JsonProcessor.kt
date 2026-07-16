package com.smsgatewayagent.processor

import com.smsgatewayagent.model.Rule
import com.smsgatewayagent.model.SmsMessage
import org.json.JSONObject

/**
 * Parses a JSON object embedded in the SMS body and extracts fields.
 *
 * Rule config (optional):
 * ```json
 * { "paths": { "amount": "data.amount", "ref": "txn.id" } }
 * ```
 * Without `paths`, the whole JSON object is flattened into the extracted data.
 */
class JsonProcessor : Processor {
    override val type = "json"
    override val displayName = "JSON"

    override fun process(message: SmsMessage, rule: Rule, context: ProcessorContext): ProcessorResult {
        val jsonText = locateJson(message.body)
            ?: return ProcessorResult.noMatch("No JSON object found in body")

        return try {
            val root = JSONObject(jsonText)
            val config = rule.config?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
            val paths = config?.optJSONObject("paths")
            val data = JSONObject()
            if (paths != null) {
                val keys = paths.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = resolvePath(root, paths.getString(key))
                    if (value != null) data.put(key, value)
                }
            } else {
                flatten(root, "", data)
            }
            data.put("sender", message.sender)
            data.put("body", message.body)
            ProcessorResult(matched = data.length() > 2, data = data)
        } catch (e: Exception) {
            ProcessorResult.failure("JSON error: ${e.message}")
        }
    }

    /** Extract the first balanced `{...}` JSON object from arbitrary SMS text. */
    private fun locateJson(body: String): String? {
        val start = body.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until body.length) {
            when (body[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return body.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun resolvePath(root: JSONObject, path: String): Any? {
        var current: Any? = root
        for (segment in path.split('.')) {
            current = when (current) {
                is JSONObject -> if (current.has(segment)) current.get(segment) else return null
                else -> return null
            }
        }
        return current
    }

    private fun flatten(obj: JSONObject, prefix: String, out: JSONObject) {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val composite = if (prefix.isEmpty()) key else "$prefix.$key"
            when (val v = obj.get(key)) {
                is JSONObject -> flatten(v, composite, out)
                else -> out.put(composite, v)
            }
        }
    }
}
