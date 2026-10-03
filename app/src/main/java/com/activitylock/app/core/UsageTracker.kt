package com.activitylock.app.core

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import java.util.Calendar

/**
 * 使用时长统计：基于 UsageStatsManager 计算某 App 当日（0 点起）累计前台时间。
 * 需要"使用情况访问权限"（特殊权限，用户在系统设置里手动授予）。
 */
object UsageTracker {

    fun hasPermission(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openPermissionSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 当日累计前台分钟数。无权限或无记录时返回 0。 */
    fun todayMinutes(ctx: Context, pkg: String): Int {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var total = 0L
        var foregroundStart = 0L
        runCatching {
            val events = usm.queryEvents(cal.timeInMillis, System.currentTimeMillis())
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.packageName != pkg) continue
                when (e.eventType) {
                    // RESUMED=1（兼容 API 26-28 的 MOVE_TO_FOREGROUND），PAUSED=2，STOPPED=26
                    UsageEvents.Event.ACTIVITY_RESUMED -> foregroundStart = e.timeStamp
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> if (foregroundStart > 0) {
                        total += e.timeStamp - foregroundStart
                        foregroundStart = 0
                    }
                }
            }
            if (foregroundStart > 0) total += System.currentTimeMillis() - foregroundStart
        }
        return (total / 60000L).toInt()
    }
}
