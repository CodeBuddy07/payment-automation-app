package com.smsgatewayagent.pipeline

import com.smsgatewayagent.model.Rule
import com.smsgatewayagent.model.SmsMessage
import com.smsgatewayagent.processor.Processor
import com.smsgatewayagent.processor.ProcessorContext
import com.smsgatewayagent.processor.ProcessorRegistry
import com.smsgatewayagent.processor.ProcessorResult
import java.util.regex.Pattern

/** Outcome of evaluating the rule set against a message. */
data class MatchOutcome(val rule: Rule, val processor: Processor, val result: ProcessorResult)

/**
 * Applies the two-stage matching contract from the spec:
 *   1. the rule's sender pattern matches the SMS sender, and
 *   2. the rule's processor reports a match.
 * Rules are evaluated in priority order; the first full match wins.
 */
object RuleMatcher {

    fun match(message: SmsMessage, rules: List<Rule>, context: ProcessorContext): MatchOutcome? {
        for (rule in rules) {
            if (!senderMatches(rule, message.sender)) continue
            val processor = ProcessorRegistry.get(rule.processorType) ?: continue
            val result = processor.process(message, rule, context)
            if (result.matched) return MatchOutcome(rule, processor, result)
        }
        return null
    }

    fun senderMatches(rule: Rule, sender: String): Boolean {
        val pattern = rule.senderPattern.trim()
        return when (rule.senderMatchType.lowercase()) {
            "any", "" -> true
            "exact" -> sender.equals(pattern, ignoreCase = true)
            "regex" -> try {
                Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(sender).find()
            } catch (_: Exception) { false }
            else -> pattern.isEmpty() || sender.contains(pattern, ignoreCase = true) // "contains"
        }
    }
}
