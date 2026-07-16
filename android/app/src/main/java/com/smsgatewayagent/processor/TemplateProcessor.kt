package com.smsgatewayagent.processor

import com.smsgatewayagent.model.Rule
import com.smsgatewayagent.model.SmsMessage

/**
 * Matches an SMS against a trained `{placeholder}` template and extracts the named fields.
 * The template is compiled to a named-group regex (cached on the rule's `regex` column when
 * available) by [TemplateEngine].
 */
class TemplateProcessor : Processor {
    override val type = "template"
    override val displayName = "Template"

    override fun process(message: SmsMessage, rule: Rule, context: ProcessorContext): ProcessorResult {
        val template = rule.template
        if (template.isNullOrBlank()) return ProcessorResult.failure("Template is empty")

        val regex = rule.regex?.takeIf { it.isNotBlank() }
            ?: context.templateEngine.templateToRegex(template)

        return try {
            if (!context.templateEngine.matches(regex, message.body)) {
                ProcessorResult.noMatch("Body did not match template")
            } else {
                val data = context.templateEngine.extract(regex, message.body)
                data.put("sender", message.sender)
                data.put("body", message.body)
                ProcessorResult(matched = true, data = data)
            }
        } catch (e: Exception) {
            ProcessorResult.failure("Template error: ${e.message}")
        }
    }
}
