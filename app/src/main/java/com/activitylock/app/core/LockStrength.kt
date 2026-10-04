package com.activitylock.app.core

import android.content.Context
import org.json.JSONObject

/**
 * 锁定强度：用户可自定义的增强选项（借鉴不做手机控的分级强制）。
 * 临时解锁的时长与每日次数、计划窗口内的豁免禁用——
 * 每一项都堵住一条"合法绕过"的路径。
 */
object LockStrength {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("lock_strength", Context.MODE_PRIVATE)

    /** 临时解锁时长（分钟）：5/10/15/30，默认 15。 */
    fun tempUnlockMin(ctx: Context): Int =
        prefs(ctx).getInt("temp_unlock_min", 15).coerceIn(5, 30)

    fun setTempUnlockMin(ctx: Context, min: Int) {
        prefs(ctx).edit().putInt("temp_unlock_min", min.coerceIn(5, 30)).apply()
    }

    /** 每日临时解锁次数上限：-1=不限，0=禁用，N=每天 N 次。默认不限。 */
    fun tempUnlockDailyMax(ctx: Context): Int =
        prefs(ctx).getInt("temp_unlock_daily_max", -1)

    fun setTempUnlockDailyMax(ctx: Context, max: Int) {
        prefs(ctx).edit().putInt("temp_unlock_daily_max", max.coerceIn(-1, 99)).apply()
    }

    /** 计划窗口内禁用临时解锁（睡眠模式期间豁免失效）。 */
    fun noExemptInSchedule(ctx: Context): Boolean =
        prefs(ctx).getBoolean("no_exempt_in_schedule", false)

    fun setNoExemptInSchedule(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("no_exempt_in_schedule", enabled).apply()
    }

    /** 某包今日已用临时解锁次数。 */
    fun tempUnlockUsedToday(ctx: Context, pkg: String): Int {
        val p = prefs(ctx)
        val today = today()
        if (p.getString("tu_date", "") != today) return 0
        return JSONObject(p.getString("tu_counts", "{}")).optInt(pkg, 0)
    }

    /** 记录一次临时解锁使用。 */
    fun recordTempUnlock(ctx: Context, pkg: String) {
        val p = prefs(ctx)
        val today = today()
        val counts = runCatching { JSONObject(p.getString("tu_counts", "{}")) }.getOrDefault(JSONObject())
        if (p.getString("tu_date", "") != today) {
            p.edit().putString("tu_date", today).putString("tu_counts", "{}").apply()
        }
        val fresh = runCatching { JSONObject(p.getString("tu_counts", "{}")) }.getOrDefault(JSONObject())
        fresh.put(pkg, fresh.optInt(pkg, 0) + 1)
        p.edit().putString("tu_counts", fresh.toString()).putString("tu_date", today).apply()
    }

    /** 今日是否还允许临时解锁（次数上限判断）。 */
    fun tempUnlockAllowed(ctx: Context, pkg: String): Boolean {
        val max = tempUnlockDailyMax(ctx)
        if (max == 0) return false
        if (max < 0) return true
        return tempUnlockUsedToday(ctx, pkg) < max
    }

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date())
}
