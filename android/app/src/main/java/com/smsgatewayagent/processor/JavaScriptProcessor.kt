package com.smsgatewayagent.processor

import com.smsgatewayagent.model.Rule
import com.smsgatewayagent.model.SmsMessage
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * Executes user-supplied JavaScript in a hardened Rhino sandbox. Because it runs natively
 * (not on the RN runtime) it keeps working when the JS app process has been killed.
 *
 * Contract — the script body has `sms`, `body`, `sender` in scope and must `return` either:
 *   - `{ matched: boolean, data: object }`, or
 *   - a plain object of extracted fields (treated as matched when non-empty).
 *
 * Sandbox hardening: all Java class access is denied, and execution is bounded by both an
 * instruction-count threshold and a wall-clock timeout to defeat infinite loops.
 */
class JavaScriptProcessor : Processor {
    override val type = "javascript"
    override val displayName = "JavaScript"

    override fun process(message: SmsMessage, rule: Rule, context: ProcessorContext): ProcessorResult {
        val code = (rule.config?.takeIf { it.isNotBlank() }?.let { JSONObject(it).optString("code") }
            ?: rule.template)
        if (code.isNullOrBlank()) return ProcessorResult.failure("Script is empty")

        val cx = factory.enterContext()
        return try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.instructionObserverThreshold = 100_000
            startTimes[Thread.currentThread()] = System.currentTimeMillis()

            val scope: Scriptable = cx.initStandardObjects(null, true)
            cx.setClassShutter(DENY_ALL)

            val smsJson = JSONObject().apply {
                put("sender", message.sender)
                put("body", message.body)
                put("timestamp", message.timestamp)
                put("simSlot", message.simSlot)
                put("subscriptionId", message.subscriptionId)
                put("phoneNumber", message.phoneNumber ?: JSONObject.NULL)
            }

            val wrapped = """
                (function(){
                  "use strict";
                  var sms = JSON.parse(${quote(smsJson.toString())});
                  var body = sms.body, sender = sms.sender;
                  return (function(sms, body, sender){
                    $code
                  })(sms, body, sender);
                })();
            """.trimIndent()

            val raw = cx.evaluateString(scope, wrapped, "user-script", 1, null)
            interpret(toJava(raw))
        } catch (e: Exception) {
            ProcessorResult.failure("JS error: ${e.message}")
        } finally {
            startTimes.remove(Thread.currentThread())
            Context.exit()
        }
    }

    private fun interpret(value: Any?): ProcessorResult {
        val obj = value as? JSONObject ?: return ProcessorResult.noMatch("Script returned no object")
        return if (obj.has("matched") || obj.has("data")) {
            val data = obj.optJSONObject("data") ?: JSONObject()
            ProcessorResult(matched = obj.optBoolean("matched", data.length() > 0), data = data)
        } else {
            ProcessorResult(matched = obj.length() > 0, data = obj)
        }
    }

    // --------------------------------------------------- Rhino → JSON conversion
    private fun toJava(value: Any?): Any? = when (value) {
        null, Undefined.instance -> null
        is NativeArray -> JSONArray().also { arr ->
            for (i in 0 until value.length) arr.put(jsonSafe(toJava(value.get(i.toInt(), value))))
        }
        is NativeObject -> JSONObject().also { obj ->
            for (id in value.ids) {
                val key = id.toString()
                obj.put(key, jsonSafe(toJava(value.get(key, value))))
            }
        }
        is ScriptableObject -> JSONObject() // opaque host object → empty
        is Double -> if (value % 1.0 == 0.0) value.toLong() else value
        else -> value
    }

    private fun jsonSafe(v: Any?): Any = v ?: JSONObject.NULL

    private fun quote(s: String): String = JSONObject.quote(s)

    companion object {
        private const val MAX_INSTRUCTIONS = 5_000_000
        private const val MAX_WALL_MS = 2_000L
        private val startTimes = java.util.concurrent.ConcurrentHashMap<Thread, Long>()

        private val DENY_ALL = ClassShutter { _ -> false }

        private val factory = object : ContextFactory() {
            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                if (instructionCount > MAX_INSTRUCTIONS) {
                    throw Error("Script exceeded instruction limit")
                }
                val started = startTimes[Thread.currentThread()]
                if (started != null && System.currentTimeMillis() - started > MAX_WALL_MS) {
                    throw Error("Script exceeded ${MAX_WALL_MS}ms time limit")
                }
            }
        }
    }
}
