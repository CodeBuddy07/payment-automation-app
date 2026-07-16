package com.smsgatewayagent.pipeline

import android.content.Context
import com.smsgatewayagent.data.Repository
import com.smsgatewayagent.device.DeviceInfoProvider
import com.smsgatewayagent.model.QueueItem
import com.smsgatewayagent.model.SmsMessage
import com.smsgatewayagent.processor.ProcessorContext
import com.smsgatewayagent.queue.QueueScheduler
import com.smsgatewayagent.sim.SimConfigManager
import com.smsgatewayagent.sim.SimManager
import org.json.JSONObject

/**
 * The end-to-end inbound flow:
 *
 *   SMS → store (dedupe) → rule match → processor → payload build → offline queue → schedule sender
 *
 * Runs entirely natively so it is unaffected by the React Native runtime being asleep or killed.
 */
object SmsPipeline {

    fun handle(context: Context, message: SmsMessage): Boolean {
        val repo = Repository.get(context)

        // 0. Capture gate. Only SMS from a configured, record-enabled SIM slot are processed at
        // all — anything else (an unconfigured slot, a personal SIM) is dropped before it is even
        // stored, so it never touches the inbox, the queue or the server.
        if (!SimConfigManager.shouldRecord(context, message.simSlot)) {
            repo.log(
                "sim", "info",
                "Ignored SMS from slot ${message.simSlot} (sub ${message.subscriptionId}): SIM not set up for recording"
            )
            return false
        }

        // 1. Persist with dedupe. A null id means we have already processed this exact SMS.
        val id = repo.insertMessageIfNew(message)
        if (id == null) {
            repo.log("system", "info", "Duplicate SMS ignored from ${message.sender}")
            return false
        }
        val stored = message.copy(id = id)

        // 2. Evaluate rules (sender + processor).
        val rules = repo.getEnabledRules()
        val outcome = RuleMatcher.match(stored, rules, ProcessorContext())

        if (outcome == null) {
            repo.updateMessageProcessing(id, null, null, null)
            repo.updateMessageWebhookStatus(id, "none", 0)
            return false
        }

        val parsed = outcome.result.data
        repo.updateMessageProcessing(id, outcome.rule.id, outcome.processor.type, parsed.toString())
        repo.incrementMatchCount(outcome.rule.id)

        // 3. Resolve destination webhook(s). A rule that names a specific webhook targets only that
        // one; otherwise the message fans out to every enabled webhook, so all configured servers
        // receive it (not just the default/first).
        val targets = outcome.rule.webhookId
            ?.let { id -> repo.getWebhook(id)?.let { listOf(it) } ?: emptyList() }
            ?: repo.getEnabledWebhooks()
        if (targets.isEmpty()) {
            repo.updateMessageWebhookStatus(id, "no_webhook", 0)
            repo.log("webhook", "warn", "Rule '${outcome.rule.name}' matched but no webhook configured")
            return true
        }

        // 4. Build the payload once (custom template or standard envelope), enriched with the
        // station identity and the services this SIM slot is assigned to.
        val deviceId = DeviceInfoProvider.deviceId(context)
        val simInfo = SimManager.simInfoForSubscription(context, stored.subscriptionId)
        val station = SimConfigManager.stationBadge(context)
        val services = SimConfigManager.servicesForSlot(context, stored.simSlot)
        val stationName = SimConfigManager.stationName(context)
        val payloadString = outcome.rule.payloadTemplate?.takeIf { it.isNotBlank() }?.let {
            PayloadBuilder.render(it, PayloadBuilder.variables(stored, parsed, deviceId, simInfo, stationName, services))
        } ?: PayloadBuilder.defaultPayload(stored, parsed, deviceId, simInfo, station, services).toString()

        // 5. Enqueue one delivery per destination for guaranteed, ordered, retried delivery. The
        // idempotency key includes the webhook id so each server dedupes its own copy independently.
        val maxRetries = repo.getSetting("max_retries", "10")?.toIntOrNull() ?: 10
        for (webhook in targets) {
            repo.enqueue(
                QueueItem(
                    messageId = id,
                    webhookId = webhook.id,
                    webhookUrl = webhook.url,
                    payload = payloadString,
                    idempotencyKey = "${stored.hash}:${webhook.id}",
                    maxRetries = maxRetries
                )
            )
        }
        repo.updateMessageWebhookStatus(id, "queued", 0)
        repo.log("queue", "info", "Queued delivery to ${targets.size} webhook(s) for rule '${outcome.rule.name}'")

        // 6. Kick the background sender (network-constrained WorkManager job).
        QueueScheduler.scheduleNow(context)
        return true
    }

    /** Used by the Rule Testing screen to dry-run a rule without sending anything. */
    fun test(context: Context, message: SmsMessage): JSONObject {
        val repo = Repository.get(context)
        val rules = repo.getEnabledRules()
        val outcome = RuleMatcher.match(message, rules, ProcessorContext())
        return JSONObject().apply {
            put("matched", outcome != null)
            if (outcome != null) {
                put("ruleId", outcome.rule.id)
                put("ruleName", outcome.rule.name)
                put("processor", outcome.processor.type)
                put("data", outcome.result.data)
                put("errors", outcome.result.errors)
            }
        }
    }
}
