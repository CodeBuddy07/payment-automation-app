package com.smsgatewayagent.queue

import android.content.Context
import com.smsgatewayagent.data.Repository
import com.smsgatewayagent.model.QueueItem
import com.smsgatewayagent.model.Webhook
import com.smsgatewayagent.security.Crypto
import com.smsgatewayagent.security.SecurePrefs
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Outcome of a single delivery attempt. */
data class SendResult(val success: Boolean, val statusCode: Int, val error: String?, val retriable: Boolean)

/**
 * Delivers a queued payload over HTTPS using OkHttp. Adds bearer auth, custom headers, an
 * HMAC-SHA256 signature with timestamp + nonce (replay protection) and an idempotency key.
 */
object WebhookSender {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun send(context: Context, item: QueueItem): SendResult {
        val repo = Repository.get(context)
        val webhook = item.webhookId?.let { repo.getWebhook(it) }
        val allowInsecure = repo.getSetting("allow_insecure", "false") == "true"

        val url = item.webhookUrl
        if (!url.startsWith("https://", ignoreCase = true) && !allowInsecure) {
            return SendResult(false, 0, "Blocked non-HTTPS URL (enable insecure mode to override)", retriable = false)
        }

        return try {
            val request = buildRequest(context, item, webhook, url)
            client.newCall(request).execute().use { response ->
                val code = response.code
                when {
                    code in 200..299 -> SendResult(true, code, null, retriable = false)
                    code == 408 || code == 429 || code in 500..599 ->
                        SendResult(false, code, "HTTP $code: ${response.message}", retriable = true)
                    else -> SendResult(false, code, "HTTP $code: ${response.message}", retriable = false)
                }
            }
        } catch (e: Exception) {
            // Network / TLS / timeout failures are transient → retriable.
            SendResult(false, 0, e.message ?: e.javaClass.simpleName, retriable = true)
        }
    }

    private fun buildRequest(context: Context, item: QueueItem, webhook: Webhook?, url: String): Request {
        val secure = SecurePrefs.get(context)
        val body = item.payload.toRequestBody(JSON)
        val builder = Request.Builder().url(url).post(body)

        val headers = Headers.Builder()
        headers.add("Content-Type", "application/json")
        headers.add("X-Idempotency-Key", item.idempotencyKey)
        headers.add("X-Agent", "SMSGatewayAgent")

        // Custom static headers.
        webhook?.headers?.takeIf { it.isNotBlank() }?.let {
            val obj = JSONObject(it)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                headers.add(k, obj.optString(k))
            }
        }

        // Bearer token (secret resolved from Keystore-backed storage).
        secure.get(webhook?.bearerRef)?.let { headers.add("Authorization", "Bearer $it") }

        // HMAC signature + replay-protection headers.
        secure.get(webhook?.hmacRef)?.let { secret ->
            val ts = (System.currentTimeMillis() / 1000).toString()
            val nonce = Crypto.nonce()
            val signature = Crypto.hmacSha256(secret, "$ts.$nonce.${item.payload}")
            headers.add("X-Timestamp", ts)
            headers.add("X-Nonce", nonce)
            headers.add("X-Signature", "sha256=$signature")
        }

        return builder.headers(headers.build()).build()
    }
}
