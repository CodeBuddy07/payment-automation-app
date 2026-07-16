package com.smsgatewayagent.processor

import org.json.JSONArray
import org.json.JSONObject

/**
 * The plugin registry. Built-in processors are registered on first access; future processors
 * (Payment, AI, HTTP, custom user processors) register themselves here via [register] without
 * touching the pipeline, the rule engine, or the UI.
 */
object ProcessorRegistry {

    private val processors = LinkedHashMap<String, Processor>()

    init {
        register(RawSmsProcessor())
        register(TemplateProcessor())
        register(RegexProcessor())
        register(JsonProcessor())
        register(JavaScriptProcessor())
    }

    /** Add or replace a processor. Idempotent and safe to call from app start-up extensions. */
    fun register(processor: Processor) {
        processors[processor.type] = processor
    }

    fun get(type: String): Processor? = processors[type]

    fun all(): List<Processor> = processors.values.toList()

    /** Descriptor list consumed by the Processor Management screen. */
    fun describe(): JSONArray = JSONArray().apply {
        processors.values.forEach {
            put(JSONObject().apply {
                put("type", it.type)
                put("displayName", it.displayName)
            })
        }
    }
}
