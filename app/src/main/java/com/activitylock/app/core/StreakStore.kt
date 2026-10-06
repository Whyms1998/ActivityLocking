package com.activitylock.app.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 连续守护天数（streak）：ADHD 正反馈的核心指标。
 * 破功 = 当日发生超额（限额 App 用超）或专注会话提前退出；
 * 守护天数 = 从昨天往回数连续未破功的天数，今天破功不影响已累积的天数，
 * 但今天本身不计入（当天结束时才算"又守住一天"）。
 */
object StreakStore {

    private const val NAME = "streak_state"
    private const val MAX_LOOKBACK_DAYS = 400

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private fun dateStr(cal: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)

    private fun today(): String = dateStr(Calendar.getInstance())

    /** 记录一次破功（超额发生 / 专注提前退出）。 */
    fun markBroken(ctx: Context) {
        val p = prefs(ctx)
        val next = (p.getStringSet("broken", emptySet()) ?: emptySet()) + today()
        p.edit().putStringSet("broken", next).apply()
    }

    fun todayBroken(ctx: Context): Boolean =
        today() in (prefs(ctx).getStringSet("broken", emptySet()) ?: emptySet())

    /**
     * 连续守护天数：昨天、前天……往回数连续未破功的天数。
     * 惰性记录首次使用日期，避免把安装前的日子算进去。
     */
    fun streakDays(ctx: Context): Int {
        val p = prefs(ctx)
        val start = p.getString("start_date", null) ?: run {
            p.edit().putString("start_date", today()).apply()
            today()
        }
        val broken = p.getStringSet("broken", emptySet()) ?: emptySet()
        val cal = Calendar.getInstance()
        var count = 0
        for (i in 1..MAX_LOOKBACK_DAYS) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
            val d = dateStr(cal)
            if (d <= start) break // 回到首次使用日：之前的天数不计入
            if (d in broken) break
            count++
        }
        return count
    }
}
