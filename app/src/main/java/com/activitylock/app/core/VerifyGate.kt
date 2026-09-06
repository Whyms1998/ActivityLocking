package com.activitylock.app.core

import android.content.Context

/**
 * 解锁验证配置：为自律服务，解锁前要求等待一段时间或输入指定密码。
 */
object VerifyGate {

    private const val NAME = "unlock_verify"
    const val METHOD_DELAY = "delay"
    const val METHOD_PASSWORD = "password"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    fun method(ctx: Context): String = prefs(ctx).getString("method", METHOD_DELAY)!!

    fun delaySeconds(ctx: Context): Int = prefs(ctx).getInt("delay_sec", 5)

    fun password(ctx: Context): String = prefs(ctx).getString("password", "") ?: ""

    fun save(ctx: Context, enabled: Boolean, method: String, password: String, delaySec: Int) {
        prefs(ctx).edit()
            .putBoolean("enabled", enabled)
            .putString("method", method)
            .putString("password", password)
            .putInt("delay_sec", delaySec)
            .apply()
    }
}
