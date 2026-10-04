package com.activitylock.app.core

import android.content.Context
import org.json.JSONObject

/**
 * 临时解锁豁免：包名 -> 截止时间（epoch ms）。
 * 豁免期内 Watcher 放行该 App 的所有启动；过期自动失效，无需应用在线。
 */
object UnlockExemptions {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("exempt_state", Context.MODE_PRIVATE)

    /** 未过期的豁免集合。 */
    fun active(ctx: Context): Map<String, Long> {
        val p = prefs(ctx)
        val raw = p.getString("map", null) ?: return emptyMap()
        val now = System.currentTimeMillis()
        val out = linkedMapOf<String, Long>()
        runCatching {
            val o = JSONObject(raw)
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val until = o.optLong(k, 0)
                if (until > now) out[k] = until
            }
        }
        return out
    }

    fun add(ctx: Context, pkg: String, untilMs: Long) {
        val p = prefs(ctx)
        val cur = active(ctx).toMutableMap()
        cur[pkg] = untilMs
        p.edit().putString("map", JSONObject(cur as Map<*, *>).toString())
            .putString("date", java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date()))
            .apply()
    }

    fun remove(ctx: Context, pkg: String) {
        val p = prefs(ctx)
        val cur = active(ctx).toMutableMap() - pkg
        p.edit().putString("map", JSONObject(cur as Map<*, *>).toString()).apply()
    }

    /** 清理过期项（有变化时返回 true，调用方应重写拦截清单）。 */
    fun clearExpired(ctx: Context): Boolean {
        val p = prefs(ctx)
        val raw = runCatching { JSONObject(p.getString("map", "{}")) }.getOrDefault(JSONObject())
        val now = System.currentTimeMillis()
        val kept = JSONObject()
        var removed = false
        val keys = raw.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val until = raw.optLong(k, 0)
            if (until > now) kept.put(k, until) else removed = true
        }
        if (removed) p.edit().putString("map", kept.toString()).apply()
        return removed
    }
}
