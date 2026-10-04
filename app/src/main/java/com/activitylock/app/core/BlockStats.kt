package com.activitylock.app.core

import com.activitylock.app.core.ShizukuManager

/**
 * 拦截统计读取：Watcher 把拦截计数落盘在 blocked_stats.json（每 20 秒），
 * 这里经 Shizuku 读取展示。
 */
object BlockStats {

    const val PATH = "/sdcard/Android/data/com.activitylock.app/files/blocked_stats.json"

    data class Entry(val time: Long, val pkg: String, val comp: String)

    data class Stats(
        val date: String,
        val total: Int,
        val today: Int,
        val apps: Map<String, Int>,
        val daily: Map<String, Int>,
        val recent: List<Entry>,
    )

    /** 最近一次被拦截的包名（取 recent 最新一条）。 */
    fun lastBlockedPkg(): String? = read()?.recent?.firstOrNull()?.pkg

    fun read(): Stats? {
        val r = ShizukuManager.run("cat", PATH)
        if (r.code != 0 && r.output.isBlank()) return null
        return runCatching {
            val o = org.json.JSONObject(r.output)
            val apps = linkedMapOf<String, Int>()
            val a = o.optJSONObject("apps")
            a?.let { json ->
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    apps[k] = json.optInt(k, 0)
                }
            }
            val daily = linkedMapOf<String, Int>()
            val d = o.optJSONObject("daily")
            d?.let { json ->
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    daily[k] = json.optInt(k, 0)
                }
            }
            val recent = mutableListOf<Entry>()
            val arr = o.optJSONArray("recent")
            arr?.let {
                for (i in 0 until it.length()) {
                    val parts = it.optString(i).split("|")
                    if (parts.size >= 3) {
                        recent.add(Entry(parts[0].toLongOrNull() ?: 0L, parts[1], parts[2]))
                    }
                }
            }
            Stats(
                date = o.optString("date"),
                total = o.optInt("total", 0),
                today = apps.values.sum(),
                apps = apps,
                daily = daily,
                recent = recent,
            )
        }.getOrNull()
    }
}
