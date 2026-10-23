package com.activitylock.app.core

import android.content.Context

/**
 * 锁定强度：用户可自定义的增强选项（借鉴不做手机控的分级强制）。
 * 临时解锁的时长与每日次数、计划窗口内的豁免禁用——
 * 每一项都堵住一条"合法绕过"的路径。
 */
object LockStrength {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("lock_strength", Context.MODE_PRIVATE)

    /** 临时解锁时长（分钟）：5/10/15/30，默认 15。 —— 旧接口，仅为兼容保留。 */
    fun tempUnlockMin(ctx: Context): Int = tempUnlockSec(ctx) / 60

    fun setTempUnlockMin(ctx: Context, min: Int) {
        setTempUnlockSec(ctx, min.coerceIn(5, 30) * 60)
    }

    /**
     * ★ v1.24.8：临时解锁时长改存**秒**，上限从 30 分钟放宽到 24 小时。
     *
     * 用户 2026-10-13 反馈：「临时解锁时长把 30 分钟换成自定义时间的选项」——
     * 原来 `coerceIn(5, 30)` 把上限钉死在 30 分钟，想临时放行 1 小时根本做不到。
     * 改用 [com.activitylock.app.ui.components.DurationField] 后单位可以是秒/分/时，
     * 数据层自然要跟着改成秒。
     *
     * 兼容策略：新键 `temp_unlock_sec` 存秒；旧键 `temp_unlock_min` 仍在，
     * 读取时若新键不存在、却存在旧键，则按旧值（分钟）换算成秒并写回新键。
     */
    fun tempUnlockSec(ctx: Context): Int {
        val p = prefs(ctx)
        if (!p.contains("temp_unlock_sec")) {
            val legacy = p.getInt("temp_unlock_min", 15)
            val sec = (legacy * 60).coerceIn(1, MAX_TEMP_UNLOCK_SEC)
            p.edit().putInt("temp_unlock_sec", sec).apply()
            return sec
        }
        return p.getInt("temp_unlock_sec", 900).coerceIn(1, MAX_TEMP_UNLOCK_SEC)
    }

    fun setTempUnlockSec(ctx: Context, sec: Int) {
        val safe = sec.coerceIn(1, MAX_TEMP_UNLOCK_SEC)
        prefs(ctx).edit()
            .putInt("temp_unlock_sec", safe)
            // 旧键只为兼容极老版本读取（旧版上限 30 分钟），主值始终看 temp_unlock_sec。
            .putInt("temp_unlock_min", (safe / 60).coerceIn(5, 30))
            .apply()
    }

    /**
     * 临时解锁时长上限（防 Int 溢出，约 3 年）—— **不是业务限制**。
     *
     * ★ v1.24.9 收口：原为 24 小时（且下限 60 秒）。但输入侧
     *   [com.activitylock.app.ui.components.DurationPickerDialog] 已放开为
     *   「任意数字都可以」，用户填「3 天」时弹层回显「实际时长 3 天」、
     *   存下来却是 1 天 —— 回显与实际不一致，比静默还糟。
     *   现与 [com.activitylock.app.ui.components.MAX_DURATION_SEC] 同口径；
     *   下限 1 秒（0 秒的豁免等于没有豁免），与 [VerifyGate.safeDelaySec] 一致。
     */
    const val MAX_TEMP_UNLOCK_SEC = 100_000_000

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

    /**
     * 今日已用临时解锁次数（**全局口径**）。
     *
     * ★ v1.24.9 修复：此前是「按包」的 JSON 计数（`tu_counts`）+ 三个函数
     *   [tempUnlockUsedToday] / [recordTempUnlock] / [tempUnlockAllowed]，
     *   但它们**全项目零调用点** —— 设置页存得下上限、没人读它：
     *   用户选了「每天 1 次」照样能解锁无限次，磁贴也永远可点（假开关）。
     *
     * 语义也已修正：临时解锁的唯一入口是全局磁贴，一次动作把所有锁定界面
     * 一起放行，不存在「单独给某个包临时解锁」的操作 —— 所以计数必须是
     * **全局**的（一次临时解锁 = 1 次），而不是按包各记一份。
     */
    fun tempUnlockUsedToday(ctx: Context): Int {
        val p = prefs(ctx)
        if (p.getString("tu_date", "") != today()) return 0
        return p.getInt("tu_used_today", 0)
    }

    /** 记录一次临时解锁（全局计数，跨日自动归零）。 */
    @Synchronized
    fun recordTempUnlock(ctx: Context) {
        val p = prefs(ctx)
        val t = today()
        val used = if (p.getString("tu_date", "") != t) 0 else p.getInt("tu_used_today", 0)
        p.edit().putString("tu_date", t).putInt("tu_used_today", used + 1).apply()
    }

    /** 今日是否还允许临时解锁（次数上限判断）。`-1`=不限，`0`=禁用，`N`=每天 N 次。 */
    fun tempUnlockAllowed(ctx: Context): Boolean {
        val max = tempUnlockDailyMax(ctx)
        if (max == 0) return false
        if (max < 0) return true
        return tempUnlockUsedToday(ctx) < max
    }

    /**
     * 当日 key（`yyyy-MM-dd`）。
     *
     * ★ v1.24.13：**必须显式给 Locale.US**。不指定 locale 时，在泰历 / 阿拉伯语等
     *   区域会格式化成非 ASCII 数字或非公历年份（例：泰历把 2026 写成 2569）——
     *   日期 key 一旦与 `QuotaState` 等其它模块（那边本来就传了 Locale.US）不一致，
     *   跨模块比对就会出错。这里与 [QuotaState.today] 对齐。
     */
    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
}
