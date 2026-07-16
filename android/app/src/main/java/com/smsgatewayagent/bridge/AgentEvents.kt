package com.smsgatewayagent.bridge

import android.content.Context
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * Bridges native events (new SMS, queue changes) to JavaScript when the RN runtime is alive.
 * When the app process is running headless these calls are simply no-ops — the native pipeline
 * keeps working regardless.
 */
object AgentEvents {

    @Volatile
    var reactContext: ReactApplicationContext? = null

    private fun emit(name: String, payload: WritableMap) {
        val ctx = reactContext ?: return
        if (!ctx.hasActiveReactInstance()) return
        try {
            ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(name, payload)
        } catch (_: Exception) {
            // UI not ready; ignore.
        }
    }

    fun emitSmsReceived(context: Context, sender: String) {
        val map = Arguments.createMap().apply {
            putString("sender", sender)
            putDouble("timestamp", System.currentTimeMillis().toDouble())
        }
        emit("sms_received", map)
    }

    fun emitQueueChanged() {
        emit("queue_changed", Arguments.createMap())
    }
}
