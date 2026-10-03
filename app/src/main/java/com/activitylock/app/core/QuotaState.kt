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
        val p = prefs(ctx)
        if (p.getString("date", null) != today()) {
            p.edit().putString("date", today()).putStringSet("over", setOf(pkg)).apply()
        } else {
            val next = (p.getStringSet("over", emptySet()) ?: emptySet()) + pkg
            p.edit().putString("date", today()).putStringSet("over", next).apply()
        }
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
}
