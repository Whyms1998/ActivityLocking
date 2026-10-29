package com.activitylock.app.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 一个被拦下的监督对象。
 *
 * [reason] 取值：
 *  - forbid      禁止使用
 *  - window_sec  本时段累计时长用完
 *  - window_cnt  本时段打开次数用完
 *  - day_sec     当天累计时长用完
 *  - day_cnt     当天打开次数用完
 */
data class SuperviseBlock(
    val pkg: String,
    val cls: String?,
    val reason: String,
    val taskName: String,
    val usedSec: Long = 0,
    val limitSec: Long = 0,
    val usedCnt: Int = 0,
    val limitCnt: Int = 0,
) {
    val key: String get() = if (cls.isNullOrBlank()) pkg else "$pkg/$cls"
    val isWholeApp: Boolean get() = cls.isNullOrBlank()
}

/**
 * 监督判定引擎（★ v1.32.0）。
 *
 * 设计要点：**门禁式**——每轮把所有任务 × 所有对象重新算一遍「现在能不能进」，
 * 而不是「达标瞬间写一个只进不出的超额集合」。
 *
 * 旧实现（v1.30~v1.31）用 `QuotaState.overPackages` 记录超额状态，只在触线时写入、
 * 次日惰性清空，导致「调大额度不解除」「取消了限额还被拦」这类只进不出的 bug，
 * 还得用 `reevaluateOver` 打补丁。门禁式判定没有状态：读数实时从 UsageStats 现算，
 * 改额度 / 跨天 / 时段结束 / 关任务 天然正确。
 */
object SuperviseEngine {

    private const val TAG = "Supervise"
    private const val PREFS = "supervise_state"
    private const val KEY_BLOCKS = "blocks"

    @Volatile
    private var cache: List<SuperviseBlock> = emptyList()

    /** 当前拦截集合（供 AmsController.writeRules 与额度页读取）。 */
    fun blocks(ctx: Context): List<SuperviseBlock> {
        if (cache.isEmpty()) cache = load(ctx)
        return cache
    }

    fun blockFor(ctx: Context, key: String): SuperviseBlock? =
        blocks(ctx).firstOrNull { it.key == key }

    /**
     * 跑一轮判定。返回 true 表示拦截集合发生变化（调用方需要重写 blocked.json）。
     */
    @Synchronized
    fun tick(ctx: Context): Boolean {
        AppStore.ensureInit(ctx)
        val now = System.currentTimeMillis()
        val nowMin = minuteOfDay(now)
        val dayStart = dayStartMs(now)

        val tasks = AppStore.tasks.filter {
            it.enabled && it.rules.active && it.targets.isNotEmpty()
        }
        if (tasks.isEmpty()) return publish(ctx, emptyList())

        val hasPerm = UsageTracker.hasPermission(ctx)
        // 只有「禁止使用」不依赖使用统计；没授权时其余维度一律不判，避免误锁
        if (!hasPerm) {
            val forbidOnly = ArrayList<SuperviseBlock>()
            for (t in tasks) {
                if (!t.inWindow(nowMin) || !t.rules.forbid) continue
                for (tg in t.targets) {
                    forbidOnly.add(SuperviseBlock(tg.pkg, tg.cls, "forbid", t.name))
                }
            }
            return publish(ctx, forbidOnly)
        }

        // 所有对象：整包集合 + 单界面集合
        val wholePkgs = HashSet<String>()
        val compKeys = HashSet<String>()
        for (t in tasks) for (tg in t.targets) {
            if (tg.isWholeApp) wholePkgs.add(tg.pkg) else compKeys.add(tg.key)
        }

        // 当天读数：所有对象一次算完
        val dayStat = runCatching {
            UsageTracker.usageForAll(ctx, dayStart, wholePkgs, compKeys)
        }.getOrDefault(emptyMap())

        val next = ArrayList<SuperviseBlock>()
        for (task in tasks) {
            if (!task.inWindow(nowMin)) continue
            val r = task.rules
            // 时段读数：全天任务直接复用当天读数，省一次全天遍历
            val winStat = if (task.windowStart == 0 && task.windowEnd == 1439) dayStat else runCatching {
                UsageTracker.usageForAll(ctx, windowStartMs(task, now), wholePkgs, compKeys)
            }.getOrDefault(emptyMap())

            for (tg in task.targets) {
                val ds = dayStat[tg.key] ?: TargetUsage()
                val ws = winStat[tg.key] ?: TargetUsage()
                val block = when {
                    r.forbid -> SuperviseBlock(tg.pkg, tg.cls, "forbid", task.name)
                    r.windowLimitSec != null && ws.sec >= r.windowLimitSec ->
                        SuperviseBlock(
                            tg.pkg, tg.cls, "window_sec", task.name,
                            usedSec = ws.sec, limitSec = r.windowLimitSec.toLong()
                        )
                    r.windowLimitCnt != null && ws.opens >= r.windowLimitCnt ->
                        SuperviseBlock(
                            tg.pkg, tg.cls, "window_cnt", task.name,
                            usedCnt = ws.opens, limitCnt = r.windowLimitCnt
                        )
                    r.dayLimitSec != null && ds.sec >= r.dayLimitSec ->
                        SuperviseBlock(
                            tg.pkg, tg.cls, "day_sec", task.name,
                            usedSec = ds.sec, limitSec = r.dayLimitSec.toLong()
                        )
                    r.dayLimitCnt != null && ds.opens >= r.dayLimitCnt ->
                        SuperviseBlock(
                            tg.pkg, tg.cls, "day_cnt", task.name,
                            usedCnt = ds.opens, limitCnt = r.dayLimitCnt
                        )
                    else -> null
                }
                if (block != null) next.add(block)
            }
        }
        return publish(ctx, next)
    }

    /** 对比并落盘；返回是否有变化。 */
    private fun publish(ctx: Context, next: List<SuperviseBlock>): Boolean {
        val before = cache.associateBy { it.key to it.reason }
        val after = next.associateBy { it.key to it.reason }
        val changed = before.keys != after.keys
        cache = next
        if (changed) {
            runCatching {
                val arr = JSONArray()
                for (b in next) {
                    arr.put(
                        JSONObject()
                            .put("p", b.pkg)
                            .put("c", b.cls ?: "")
                            .put("rs", b.reason)
                            .put("t", b.taskName)
                            .put("us", b.usedSec)
                            .put("ls", b.limitSec)
                            .put("uc", b.usedCnt)
                            .put("lc", b.limitCnt)
                    )
                }
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_BLOCKS, arr.toString()).apply()
                Log.i(TAG, "blocks=" + next.joinToString { "${it.key}:${it.reason}" })
            }
        }
        return changed
    }

    private fun load(ctx: Context): List<SuperviseBlock> = runCatching {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BLOCKS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val pkg = o.optString("p")
            if (pkg.isBlank()) null else SuperviseBlock(
                pkg = pkg,
                cls = o.optString("c").takeIf { it.isNotBlank() },
                reason = o.optString("rs", "day_sec"),
                taskName = o.optString("t"),
                usedSec = o.optLong("us"),
                limitSec = o.optLong("ls"),
                usedCnt = o.optInt("uc"),
                limitCnt = o.optInt("lc"),
            )
        }
    }.getOrDefault(emptyList())

    /** 任务全删/关闭后清空拦截状态。 */
    @Synchronized
    fun clear(ctx: Context) {
        if (cache.isEmpty()) return
        cache = emptyList()
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_BLOCKS).apply()
        }
    }

    // ---------------- 时间工具 ----------------

    fun minuteOfDay(ts: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun dayStartMs(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 任务本次生效时段的起点（未到点则回退到昨天同一时刻，用于跨夜窗口）。 */
    fun windowStartMs(task: SuperviseTask, now: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, task.windowStart / 60)
            set(Calendar.MINUTE, task.windowStart % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var t = c.timeInMillis
        if (t > now) t -= 24L * 3600_000L
        return maxOf(t, dayStartMs(now))
    }
}
