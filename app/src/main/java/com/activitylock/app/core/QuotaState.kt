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

    // ---------------- ★ v1.31.0：每日打开次数维度 ----------------
    // 语义变更（原「解锁次数」废弃）：计数 = 系统事件统计的入口界面打开数
    // （UsageTracker.todayOpenCount），覆盖验证解锁 / 跳转直达 / 通知点开一切入口。
    // 不再需要 App 侧落盘计数（cnt_used 已删）——系统 UsageEvents 就是权威数据源。
    // 限额配置仍在 LockRule.quotaCnt；超额仍走 markOver → 整包拦截 → 额度页管道。

    /** 规则里配置了每日打开次数上限的包名 -> 次数（同一包多条规则取最大值）。 */
    fun quotaCntPackages(rules: List<LockRule>): Map<String, Int> =
        rules.mapNotNull { r -> r.quotaCnt?.let { r.packageName to it } }
            .groupBy({ it.first }) { it.second }
            .mapValues { (_, v) -> v.max() }

    /** 当日各包已放行的解锁次数（跨天惰性归零）。格式 "pkg:cnt,pkg:cnt"。 */


}
