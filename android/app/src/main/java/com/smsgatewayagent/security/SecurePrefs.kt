package com.smsgatewayagent.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-backed storage for secrets (bearer tokens, HMAC keys). The database only ever holds
 * a *reference* string; the secret itself lives here, encrypted with an AES-256 master key
 * sealed in the Android Keystore.
 */
class SecurePrefs private constructor(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "sms_gateway_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun put(ref: String, secret: String?) {
        if (secret == null) prefs.edit().remove(ref).apply()
        else prefs.edit().putString(ref, secret).apply()
    }

    fun get(ref: String?): String? = ref?.let { prefs.getString(it, null) }

    fun remove(ref: String) = prefs.edit().remove(ref).apply()

    /** True when a secret is stored, without revealing it (used by the UI to show "set"). */
    fun has(ref: String?): Boolean = ref != null && prefs.contains(ref)

    companion object {
        @Volatile
        private var instance: SecurePrefs? = null

        fun get(context: Context): SecurePrefs =
            instance ?: synchronized(this) {
                instance ?: SecurePrefs(context.applicationContext).also { instance = it }
            }
    }
}
