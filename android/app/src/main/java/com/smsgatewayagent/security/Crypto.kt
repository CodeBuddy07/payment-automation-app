package com.smsgatewayagent.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Cryptographic helpers for webhook integrity: HMAC-SHA256 signatures + replay nonces. */
object Crypto {

    /** Lower-case hex HMAC-SHA256 of [payload] using [secret]. */
    fun hmacSha256(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).toHex()
    }

    /** Random 128-bit nonce (hex) included in each delivery to let servers reject replays. */
    fun nonce(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b))
        return sb.toString()
    }
}
