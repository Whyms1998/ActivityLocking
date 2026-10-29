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
        // ★ unsafeCheckOpNoThrow 是 API 29 新增；minSdk=26，低版本必须回退 checkOpNoThrow，
        //   否则在 Android 8/9 上抛 NoSuchMethodError（QuotaService 主循环会直接崩）。
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
            )
        }
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

    /**
     * ★ v1.31.0：当日「打开次数」——次数限额的计数源。
     *
     * 口径：桌面入口界面的 ACTIVITY_RESUMED 事件数。为什么不用「每次回到前台」：
     * 应用内切界面（A→B activity）也会产生 RESUMED，会把「多看了几个页面」误算成
     * 「打开了几次」；而入口界面（launcher activity）只在两种情况 RESUMED——
     * 点桌面图标、或被我们的跳转拉起（酷安「直达搜索页」的深链就落在入口上）。
     * 这正好等于用户感知的「打开」。
     *
     * entryCls 为 null（没扫到入口界面）时退化为「前台会话数」：离开前台
     * （PAUSED/STOPPED）之后的第一个 RESUMED 计 1 次——宁多勿漏。
     * 系统事件是权威数据源：无论走验证解锁、跳转直达还是通知点开，都算打开。
     */
    fun todayOpenCount(ctx: Context, pkg: String, entryCls: String?): Int {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var count = 0
        var leftForeground = true
        runCatching {
            val events = usm.queryEvents(cal.timeInMillis, System.currentTimeMillis())
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.packageName != pkg) continue
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (entryCls != null) {
                            if (e.className == entryCls) count++
                        } else if (leftForeground) {
                            count++
                        }
                        leftForeground = false
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> leftForeground = true
                }
            }
        }
        return count
    }
}
