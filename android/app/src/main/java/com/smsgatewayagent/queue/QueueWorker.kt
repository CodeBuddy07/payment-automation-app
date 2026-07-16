package com.smsgatewayagent.queue

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.smsgatewayagent.data.Repository
import kotlin.math.min
import kotlin.math.pow

/**
 * Drains the persistent delivery queue. Guarantees:
 *  - FIFO ordering (claims by ascending id)
 *  - exponential backoff per item (`next_retry_at`)
 *  - bounded retries → dead-letter on exhaustion
 *  - idempotent: surviving items are simply re-claimed on the next run, so reboot / process
 *    death never loses or double-finalises a message.
 */
class QueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = Repository.get(applicationContext)
        // Recover anything a previously-killed worker left mid-flight.
        repo.recoverStaleInProgress(System.currentTimeMillis() - STALE_IN_PROGRESS_MS)
        var iterations = 0
        try {
            while (iterations++ < MAX_ITERATIONS) {
                val now = System.currentTimeMillis()
                val batch = repo.claimDue(now, BATCH_SIZE)
                if (batch.isEmpty()) break

                for (item in batch) {
                    val result = WebhookSender.send(applicationContext, item)
                    when {
                        result.success -> {
                            repo.markSent(item.id, result.statusCode)
                            item.messageId?.let { repo.updateMessageWebhookStatus(it, "sent", item.retryCount) }
                            repo.log("webhook", "info", "Delivered #${item.id} (${result.statusCode})")
                        }
                        !result.retriable -> {
                            repo.markDead(item.id, result.error)
                            item.messageId?.let { repo.updateMessageWebhookStatus(it, "dead", item.retryCount) }
                            repo.log("webhook", "error", "Dead-lettered #${item.id}: ${result.error}")
                        }
                        else -> {
                            val nextCount = item.retryCount + 1
                            if (nextCount >= item.maxRetries) {
                                repo.markDead(item.id, "Max retries reached: ${result.error}")
                                item.messageId?.let { repo.updateMessageWebhookStatus(it, "dead", nextCount) }
                                repo.log("webhook", "error", "Dead-lettered #${item.id} after $nextCount tries")
                            } else {
                                val nextAt = now + backoffMillis(nextCount)
                                repo.markRetry(item.id, nextCount, nextAt, result.error, result.statusCode)
                                item.messageId?.let { repo.updateMessageWebhookStatus(it, "queued", nextCount) }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            repo.log("queue", "error", "Queue worker error: ${e.message}")
            return Result.retry()
        }

        repo.setSetting("last_sync", System.currentTimeMillis().toString())

        // Anything still pending/failed (including future-dated retries) means come back later.
        // WorkManager's own backoff handles the wait; the network constraint pauses us offline.
        val pendingStats = repo.queueStats()
        val outstanding = pendingStats.optInt("pending", 0) + pendingStats.optInt("failed", 0) +
            pendingStats.optInt("in_progress", 0)
        return if (outstanding > 0) Result.retry() else Result.success()
    }

    /** 30s · 2^(n-1) with a 6h ceiling and ±15% jitter to avoid thundering herds. */
    private fun backoffMillis(retryCount: Int): Long {
        val base = BASE_DELAY_MS * 2.0.pow((retryCount - 1).coerceAtMost(20))
        val capped = min(base, MAX_DELAY_MS.toDouble())
        val jitter = capped * 0.15 * (Math.random() - 0.5) * 2
        return (capped + jitter).toLong().coerceAtLeast(1000)
    }

    companion object {
        private const val BATCH_SIZE = 20
        private const val MAX_ITERATIONS = 50
        private const val BASE_DELAY_MS = 30_000L
        private const val MAX_DELAY_MS = 6 * 60 * 60 * 1000L
        private const val STALE_IN_PROGRESS_MS = 120_000L
    }
}
