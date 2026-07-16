package com.smsgatewayagent.device

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.smsgatewayagent.data.Repository
import org.json.JSONObject
import java.util.UUID

/** Stable device + installation identity and build metadata for payloads and diagnostics. */
object DeviceInfoProvider {

    private const val KEY_INSTALL_ID = "installation_id"

    /** Per-install UUID, generated once and persisted in the settings table. */
    fun installationId(context: Context): String {
        val repo = Repository.get(context)
        repo.getSetting(KEY_INSTALL_ID)?.let { return it }
        val id = UUID.randomUUID().toString()
        repo.setSetting(KEY_INSTALL_ID, id)
        return id
    }

    /** Hardware-derived device id (ANDROID_ID). May be overridden by the user in settings. */
    fun deviceId(context: Context): String {
        Repository.get(context).getSetting("device_id_override")?.takeIf { it.isNotBlank() }?.let { return it }
        @Suppress("HardwareIds")
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return androidId ?: installationId(context)
    }

    fun appVersion(context: Context): String = try {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pkg.versionName} (${pkg.longVersionCodeCompat()})"
    } catch (_: Exception) {
        "unknown"
    }

    fun toJson(context: Context): JSONObject = JSONObject().apply {
        put("deviceId", deviceId(context))
        put("installationId", installationId(context))
        put("model", Build.MODEL)
        put("manufacturer", Build.MANUFACTURER)
        put("androidVersion", Build.VERSION.RELEASE)
        put("sdkInt", Build.VERSION.SDK_INT)
        put("appVersion", appVersion(context))
    }

    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()
}
