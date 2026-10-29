package com.activitylock.app.core

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import java.util.Calendar

/** ★ v1.32.0：单个监督对象的当日/时段读数（秒 + 打开次数）。 */
class TargetUsage(var sec: Long = 0L, var opens: Int = 0)

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
    /**
     * ★ v1.31.2：只统计 [sinceMs, now] 区间内「入口界面 RESUMED」的次数（增量快检）。
     *
     * 为什么需要：次数限额原先挂在 60 秒巡检上，达标到真正落锁最多要等一分钟——
     * 连续点开七八次都在窗口里，用户看到的就是「限额没生效」。
     * 快循环（3 秒）用增量窗口查询，只扫这几秒的事件，代价极低。
     *
     * entryCls 为空时不适用增量（无法判断会话边界），调用方应回退 [todayOpenCount]。
     */
    fun countSince(ctx: Context, pkg: String, entryCls: String, sinceMs: Long): Int {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        var count = 0
        runCatching {
            val events = usm.queryEvents(sinceMs, System.currentTimeMillis())
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.packageName == pkg &&
                    e.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                    e.className == entryCls
                ) count++
            }
        }
        return count
    }

    /**
     * ★ v1.32.0：一次遍历统计**多个监督对象**的时长与打开次数。
     *
     * 为什么需要：监督任务可以同时管整包（抖音）和单界面（微信›朋友圈），
     * 而一轮判定要算「当天」和「时段」两套读数。若按对象逐个 queryEvents，
     * 10 个对象 × 2 个窗口 = 20 次全天遍历，3 秒一轮会明显耗电。
     * 这里一次扫描就够：先按包名集合快速过滤（绝大多数事件属于其他 App，直接跳过），
     * 再用 (pkg,cls) 维护前台起点，结算时同时累加「整包」与「单界面」两份读数。
     *
     * @param fromMs 统计起点（当天 0 点 / 时段起点）
     * @param wholePkgs 需要整包统计的包名
     * @param compKeys 需要单界面统计的 "pkg/cls"
     * @param entryClsOf 整包「打开次数」的锚点（入口 Activity）；返回 null 时退化为会话数
     */
    /**
     * ★ v1.32.0：该包此刻是否在前台（UsageStats 事件状态机）。
     *
     * 只用于「次数用完」这一种情况：正在用就让他把这一次用完，确认退出了才收尾。
     * 查询窗口取 4 小时——更久没有任何事件的包按"不在前台"处理。
     */
    fun isInForeground(ctx: Context, pkg: String): Boolean {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        var state = false
        runCatching {
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 4 * 3600_000L, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.packageName != pkg) continue
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> state = true
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> state = false
                }
            }
        }
        return state
    }

    fun usageForAll(
        ctx: Context,
        fromMs: Long,
        wholePkgs: Set<String>,
        compKeys: Set<String>,
    ): Map<String, TargetUsage> {
        val out = HashMap<String, TargetUsage>()
        if (wholePkgs.isEmpty() && compKeys.isEmpty()) return out
        val pkgs = HashSet<String>(wholePkgs)
        for (k in compKeys) pkgs.add(k.substringBefore('/'))
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        // 每个 (pkg/cls) 的前台起点；结算时同时累加整包与单界面
        val fgStart = HashMap<String, Long>()
        // 每包活跃的 Activity 计数：0 → 1 的跃迁才算「打开一次」。
        // ★ 不能用「入口 Activity 的 RESUMED」当整包计数锚点：配了「点图标直达」的 App
        //   （酷安 → 搜索页）入口会被拦截引擎拒绝，根本不会产生 RESUMED 事件，
        //   计数会永远为 0。也不能用「单个 Activity PAUSED/STOPPED」判离开，
        //   单 Activity 应用内部切页面同样会 PAUSED，会把一次打开误算成多次。
        val active = HashMap<String, Int>()

        fun stat(key: String) = out.getOrPut(key) { TargetUsage() }
        fun settle(pkg: String, cls: String, endMs: Long) {
            val ck = "$pkg/$cls"
            val s = fgStart.remove(ck) ?: return
            val delta = (endMs - s).coerceAtLeast(0)
            if (delta <= 0) return
            if (pkg in wholePkgs) stat(pkg).sec += delta
            if (ck in compKeys) stat(ck).sec += delta
        }

        runCatching {
            val events = usm.queryEvents(fromMs, System.currentTimeMillis())
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val pkg = e.packageName ?: continue
                if (pkg !in pkgs) continue
                val cls = e.className ?: ""
                val ck = "$pkg/$cls"
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        // 界面级次数：该 Activity 的每次 RESUMED 都算一次进入
                        if (ck in compKeys) stat(ck).opens++
                        // 整包次数：包内活跃 Activity 由 0 变 1 —— 即「从后台回到前台」
                        val n = (active[pkg] ?: 0) + 1
                        active[pkg] = n
                        if (n == 1 && pkg in wholePkgs) stat(pkg).opens++
                        if (!fgStart.containsKey(ck)) fgStart[ck] = e.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        settle(pkg, cls, e.timeStamp)
                        active[pkg] = ((active[pkg] ?: 0) - 1).coerceAtLeast(0)
                    }
                }
            }
            // 仍停在前台的：算到此刻
            val now = System.currentTimeMillis()
            for ((k, s) in fgStart.toList()) {
                val pkg = k.substringBefore('/')
                if (pkg in wholePkgs) stat(pkg).sec += (now - s).coerceAtLeast(0)
                if (k in compKeys) stat(k).sec += (now - s).coerceAtLeast(0)
            }
        }
        return out
    }

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
