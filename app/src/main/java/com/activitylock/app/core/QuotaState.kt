package com.activitylock.app.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 超额状态：当日额度用完的 App 集合（按天自动重置）。
 * 超额后 writeRules 会把这些 App 的拦截跳转目标替换为额度提示页。
 */
object QuotaState {

    private const val NAME = "quota_state"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** 已超额包名集合（日期不是今天时视为空，并惰性重置）。 */
    fun overPackages(ctx: Context): Set<String> {
        val p = prefs(ctx)
        if (p.getString("date", null) != today()) return emptySet()
        return p.getStringSet("over", emptySet()) ?: emptySet()
    }

    fun markOver(ctx: Context, pkg: String) {
        StreakStore.markBroken(ctx) // 超额 = 当日破功
        val p = prefs(ctx)
        if (p.getString("date", null) != today()) {
            p.edit().putString("date", today()).putStringSet("over", setOf(pkg)).apply()
        } else {
            val next = (p.getStringSet("over", emptySet()) ?: emptySet()) + pkg
            p.edit().putString("date", today()).putStringSet("over", next).apply()
        }
    }

    /** 单个包解除超额状态（验证通过后移除限额时调用）。 */
    fun clearOver(ctx: Context, pkg: String) {
        val p = prefs(ctx)
        val next = overPackages(ctx) - pkg
        p.edit().putString("date", today()).putStringSet("over", next).apply()
    }

    /** 跨天重置。返回 true 表示发生了重置（调用方应重写拦截清单）。 */
    fun resetIfNewDay(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (p.getString("date", null) != today()) {
            p.edit().putString("date", today()).putStringSet("over", emptySet()).apply()
            return true
        }
        return false
    }

    /** 规则里配置了限额的包名 -> 限额分钟数（同一包多条规则取最大值）。 */
    fun quotaPackages(rules: List<LockRule>): Map<String, Int> =
        rules.mapNotNull { r -> r.quotaMin?.let { r.packageName to it } }
            .groupBy({ it.first }) { it.second }
            .mapValues { (_, v) -> v.max() }

    // ---------------- ★ v1.30.0：每日解锁次数维度 ----------------
    // 与时长维度并存：任一触线即整包超额。次数是事件驱动（解锁放行时 +1），
    // 不需要轮询；落盘在同一个 prefs，跨重启天然不丢（无 S9 类内存问题）。

    /** 规则里配置了每日解锁次数上限的包名 -> 次数（同一包多条规则取最大值）。 */
    fun quotaCntPackages(rules: List<LockRule>): Map<String, Int> =
        rules.mapNotNull { r -> r.quotaCnt?.let { r.packageName to it } }
            .groupBy({ it.first }) { it.second }
            .mapValues { (_, v) -> v.max() }

    /** 当日各包已放行的解锁次数（跨天惰性归零）。格式 "pkg:cnt,pkg:cnt"。 */
    fun usedCounts(ctx: Context): Map<String, Int> {
        val p = prefs(ctx)
        if (p.getString("date", null) != today()) return emptyMap()
        val raw = p.getString("cnt_used", null) ?: return emptyMap()
        return raw.split(',').mapNotNull { e ->
            val i = e.indexOf(':')
            if (i <= 0) null
            else e.substring(0, i) to (e.substring(i + 1).toIntOrNull() ?: 0)
        }.toMap()
    }

    /**
     * 解锁放行计数 +1。返回 true = 已达到/超过该包的每日次数上限（调用方应 markOver）。
     * 只对配置了次数上限的包调用；首次计数或跨天时自动重置日期。
     */
    fun countUnlock(ctx: Context, pkg: String, limit: Int): Boolean {
        val p = prefs(ctx)
        val map = usedCounts(ctx).toMutableMap()
        val next = (map[pkg] ?: 0) + 1
        map[pkg] = next
        p.edit()
            .putString("date", today())
            .putString("cnt_used", map.entries.joinToString(",") { "${it.key}:${it.value}" })
            .apply()
        return next >= limit
    }

    /** 移除某包的次数计数（验证后移除次数限额时调用）。 */
    fun clearCount(ctx: Context, pkg: String) {
        val map = usedCounts(ctx)
        if (pkg !in map) return
        prefs(ctx).edit()
            .putString("date", today())
            .putString("cnt_used", (map - pkg).entries.joinToString(",") { "${it.key}:${it.value}" })
            .apply()
    }
}
