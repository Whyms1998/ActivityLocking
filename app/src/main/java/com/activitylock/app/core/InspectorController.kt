package com.activitylock.app.core

import android.content.ComponentName
import android.provider.Settings

/**
 * Activity 检视器的无障碍开关控制：通过 Shizuku 写系统安全设置注册/移除服务。
 */
object InspectorController {

    private fun serviceName(): String =
        ComponentName(AppStore.context(), InspectorService::class.java).flattenToString()

    fun isEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            AppStore.context().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val registered = enabled.split(':').any { it.equals(serviceName(), true) }
        val masterOn = Settings.Secure.getInt(
            AppStore.context().contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED, 0
        ) == 1
        return registered && masterOn
    }

    fun enable(): Boolean {
        if (!ShizukuManager.granted()) return false
        val cur = ShizukuManager.run("settings", "get", "secure", "enabled_accessibility_services")
            .output.trim()
        val merged = when {
            cur.isBlank() || cur == "null" -> serviceName()
            cur.split(':').any { it.equals(serviceName(), true) } -> null
            else -> "$cur:${serviceName()}"
        }
        if (merged != null) {
            ShizukuManager.run("settings", "put", "secure", "enabled_accessibility_services", merged)
        }
        ShizukuManager.run("settings", "put", "secure", "accessibility_enabled", "1")
        return isEnabled()
    }

    fun disable() {
        val cur = Settings.Secure.getString(
            AppStore.context().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return
        val remaining = cur.split(':').filterNot { it.equals(serviceName(), true) }
        ShizukuManager.run(
            "settings", "put", "secure",
            "enabled_accessibility_services", remaining.joinToString(":")
        )
    }
}
