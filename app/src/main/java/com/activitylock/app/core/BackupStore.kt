package com.activitylock.app.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 换机备份 / 恢复。
 *
 * ★ 为什么需要它：本 App 的全部心血都是**本地配置**——锁定清单、界面级解锁条件、
 *   监督额度、定时计划、自动锁闭、特赦配额、验证密码。换一台手机全部重新配一遍
 *   等于从零开始，而且「重新配一遍」的那个时刻恰恰是最容易被放弃的时刻。
 *
 * ★ 设计取舍：
 *  1. **白名单，绝不整目录复制**。只搬「配置」，不搬运行态：`relock_pending.json`
 *     （待重锁队列，里面有 bootId / 到期时间，搬到新机毫无意义）、`focus_state.json`
 *     （进行中的专注会话）、`supervise_state`（已是重新计算出的缓存）、
 *     `tu_date`/`tu_used_today`/`used`（当日已用次数——迁移过去等于新机当天就被扣光）。
 *  2. **每个配置文件的原文按 JSON 结构内嵌**（不是重新序列化）：导入侧依靠各 Store
 *     已有的 `ignoreUnknownKeys = true`，高版本导出的多字段能优雅降级，不会因为
 *     一个不认识的新字段就整包导入失败。
 *  3. **导入 = 完全覆盖**：备份里没有的文件会被删除。语义必须一致，否则「导入后
 *     还残留旧规则」是最难排查的那类问题。
 *  4. **导入前自动快照**（`before_import.json`），并支持一键撤销。导入是唯一一个
 *     能把几十条规则一次性换掉的入口，没有回滚点就是在赌博。
 *  5. 备份文件**包含明文解锁密码**（本 App 的密码本就以明文存于私有 SP，用于逐字符
 *     比对）。这是换机必需——不带密码的备份导入到新机等于保护被降级。代价是文件
 *     必须当作敏感信息保管，UI 上必须写明。
 */
object BackupStore {

    private const val TAG = "Backup"
    private const val FORMAT = 1
    private const val META = "backup_meta"
    private const val SNAPSHOT = "before_import.json"

    /** 参与备份的配置文件 + 用来数条目的数组字段 + 中文标签。 */
    private class FileSpec(val name: String, val arrayKey: String, val label: String)

    private val FILES = listOf(
        FileSpec("rules.json", "rules", "锁定规则"),
        FileSpec("supervise_tasks.json", "tasks", "监督任务"),
        FileSpec("groups.json", "groups", "分组"),
        FileSpec("schedules.json", "schedules", "定时计划"),
        FileSpec("sessions.json", "items", "到点跳转"),
        FileSpec("scenes.json", "scenes", "场景"),
        FileSpec("presets.json", "presets", "我的方案"),
        FileSpec("relock.json", "activities", "自动锁闭覆盖"),
        FileSpec("history.json", "entries", "历史记录"),
    )

    /**
     * 参与备份的 SharedPreferences。
     * skip = 「当日/当周进度 + 运行态」，属于不该迁移的东西。
     */
    private class PrefsSpec(val name: String, val skip: Set<String> = emptySet())

    private val PREFS = listOf(
        PrefsSpec("unlock_verify"),                                        // 全局验证（含密码）
        PrefsSpec("amnesty", setOf("used_key", "used")),                   // 特赦配额参数；已用次数不迁
        PrefsSpec("lock_strength", setOf("tu_date", "tu_used_today")),     // 临时解锁参数；今日次数不迁
        PrefsSpec("self_code"),                                            // 安全码摘要
        PrefsSpec("streak_state"),                                         // 坚持天数
        PrefsSpec("schedule_state", setOf("state")),                       // 计划配置；运行状态不迁
    )

    /** 该 SP 键是否属于「不迁移的当日进度 / 运行态」（导入时原样保留本机值）。 */
    fun isProgressKey(prefsName: String, key: String): Boolean =
        PREFS.firstOrNull { it.name == prefsName }?.skip?.contains(key) ?: false

    /** 导入异常：message 直接面向用户。 */
    class ImportException(message: String) : Exception(message)

    /** 预览摘要：导入前给用户看清楚「这一包有什么」，导入后作为结果回执。 */
    data class Preview(
        val versionName: String,
        val exportedAt: Long,
        val counts: List<Pair<String, Int>>,
        val prefGroups: Int,
    ) {
        val total: Int get() = counts.sumOf { it.second }
    }

    // ---------------------------------------------------------------- 导出

    /** 生成备份文本（纯内存，不落盘；写文件交给 SAF）。 */
    fun export(ctx: Context): String {
        val root = JSONObject()
        root.put("app", ctx.packageName)
        root.put("format", FORMAT)
        root.put("versionName", com.activitylock.app.BuildConfig.VERSION_NAME)
        root.put("exportedAt", System.currentTimeMillis())

        val files = JSONObject()
        for (spec in FILES) {
            val f = File(ctx.filesDir, spec.name)
            if (!f.exists() || f.length() == 0L) continue
            // 内嵌为真实 JSON 结构（不是转义字符串），人眼可读、体积更小。
            runCatching { files.put(spec.name, JSONObject(f.readText())) }
                .onFailure { Log.w(TAG, "export skip ${spec.name}", it) }
        }
        root.put("files", files)

        val prefs = JSONObject()
        for (spec in PREFS) {
            val all = ctx.getSharedPreferences(spec.name, Context.MODE_PRIVATE).all
            if (all.isEmpty()) continue
            val o = JSONObject()
            for ((k, value) in all) {
                if (k in spec.skip) continue
                val enc = encode(value) ?: continue
                o.put(k, enc)
            }
            if (o.length() > 0) prefs.put(spec.name, o)
        }
        root.put("prefs", prefs)

        // ★ 不在这里记「上次导出」：export() 也被云端上传复用，
        //   记在这里会让「上次导出」在纯云端同步后凭空更新（假读数）。
        return root.toString(2)
    }

    /** 建议文件名（带日期时间，便于用户在多份备份间辨认）。 */
    fun suggestFileName(): String {
        val cal = java.util.Calendar.getInstance()
        fun p2(n: Int) = if (n < 10) "0$n" else "$n"
        return "应用锁备份-${cal.get(java.util.Calendar.YEAR)}${p2(cal.get(java.util.Calendar.MONTH) + 1)}" +
            "${p2(cal.get(java.util.Calendar.DAY_OF_MONTH))}-" +
            "${p2(cal.get(java.util.Calendar.HOUR_OF_DAY))}${p2(cal.get(java.util.Calendar.MINUTE))}.json"
    }

    private fun encode(v: Any?): JSONObject? = when (v) {
        is Boolean -> JSONObject().put("t", "b").put("v", v)
        is Int -> JSONObject().put("t", "i").put("v", v)
        is Long -> JSONObject().put("t", "l").put("v", v)
        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
        is String -> JSONObject().put("t", "s").put("v", v)
        is Set<*> -> {
            val a = JSONArray()
            for (x in v) if (x != null) a.put(x.toString())
            JSONObject().put("t", "ss").put("v", a)
        }
        else -> null
    }

    // ---------------------------------------------------------------- 解析

    /** 已校验的备份包。 */
    class Parsed internal constructor(internal val root: JSONObject) {
        internal val files: JSONObject = root.optJSONObject("files") ?: JSONObject()
        internal val prefMap: JSONObject = root.optJSONObject("prefs") ?: JSONObject()

        fun preview(): Preview {
            val counts = ArrayList<Pair<String, Int>>(FILES.size)
            for (spec in FILES) {
                val n = files.optJSONObject(spec.name)
                    ?.optJSONArray(spec.arrayKey)?.length() ?: 0
                if (n > 0) counts.add(spec.label to n)
            }
            // rules.json 里还有一个非数组字段：应用级解锁条件
            val appVerify = files.optJSONObject("rules.json")?.optJSONObject("appVerify")
            if (appVerify != null && appVerify.length() > 0) {
                counts.add("应用级解锁" to appVerify.length())
            }
            return Preview(
                versionName = root.optString("versionName", "未知"),
                exportedAt = root.optLong("exportedAt", 0L),
                counts = counts,
                prefGroups = prefMap.length(),
            )
        }
    }

    /** 只校验 + 出摘要，不改动任何数据。 */
    fun parse(raw: String): Parsed {
        val root = runCatching { JSONObject(raw) }.getOrElse {
            throw ImportException("这不是有效的备份文件")
        }
        if (root.optString("app") != "com.activitylock.app") {
            throw ImportException("这个文件不是应用锁的备份")
        }
        val fmt = root.optInt("format", 0)
        if (fmt <= 0) throw ImportException("备份文件缺少版本信息")
        if (fmt > FORMAT) {
            throw ImportException("备份来自更新版本的应用，请先把应用升级到最新版")
        }
        val parsed = Parsed(root)
        if (parsed.files.length() == 0 && parsed.prefMap.length() == 0) {
            throw ImportException("备份里没有任何可导入的配置")
        }
        return parsed
    }

    // ---------------------------------------------------------------- 导入

    /**
     * 覆盖本机配置。
     *
     * 调用方（UI）必须**先过验证**：导入能把几十条规则、密码、配额一次性换成任意内容，
     * 属于最强的削弱类动作，门槛等同于批量解锁。
     */
    fun apply(ctx: Context, raw: String): Preview {
        val parsed = parse(raw)
        val snapshot = runCatching { snapshotText(ctx) }.getOrNull()
        if (snapshot != null) {
            runCatching { File(ctx.filesDir, SNAPSHOT).writeText(snapshot) }
                .onFailure { Log.w(TAG, "snapshot failed", it) }
        }
        runCatching {
            writeFiles(ctx, parsed)
            writePrefs(ctx, parsed)
        }.onFailure { e ->
            Log.w(TAG, "import failed, rolling back", e)
            if (snapshot != null) {
                runCatching {
                    val sp = parse(snapshot)
                    writeFiles(ctx, sp)
                    writePrefs(ctx, sp)
                }
            }
            AppStore.reloadFromDisk()
            throw ImportException("导入失败，已还原原配置（${e.message ?: "写入异常"}）")
        }
        AppStore.reloadFromDisk()
        reapply(ctx)
        markImported(ctx)
        return parsed.preview()
    }

    fun hasSnapshot(ctx: Context): Boolean = File(ctx.filesDir, SNAPSHOT).let { it.exists() && it.length() > 0 }

    /** 撤销上一次导入（用导入前的自动快照还原）。 */
    fun rollback(ctx: Context): Preview {
        val f = File(ctx.filesDir, SNAPSHOT)
        if (!f.exists() || f.length() == 0L) throw ImportException("没有可撤销的导入")
        val parsed = parse(f.readText())
        writeFiles(ctx, parsed)
        writePrefs(ctx, parsed)
        AppStore.reloadFromDisk()
        reapply(ctx)
        f.delete()
        return parsed.preview()
    }

    private fun writeFiles(ctx: Context, p: Parsed) {
        for (spec in FILES) {
            val target = File(ctx.filesDir, spec.name)
            val incoming = p.files.optJSONObject(spec.name)
            if (incoming != null) {
                // 先写临时文件再改名，避免写到一半被杀进程留下半截 JSON
                val tmp = File(ctx.filesDir, spec.name + ".tmp")
                tmp.writeText(incoming.toString())
                if (!tmp.renameTo(target)) {
                    target.writeText(incoming.toString())
                    tmp.delete()
                }
            } else if (target.exists()) {
                // 完全覆盖语义：备份里没有 = 本机也不该有
                target.delete()
            }
        }
    }

    /**
     * 写 SP：**保留本机的「当日进度」类字段**。
     *
     * ★ 这一条是防绕过，不是洁癖：`amnesty.used` / `tu_used_today` 是今日已用次数。
     *   如果导入时把它们清掉（第一版就是这么写的），用户只要「导入自己刚导出的备份」
     *   就能把今日特赦次数和临时解锁次数重置为 0 —— 备份导入直接变成了免费用量入口。
     *   正确语义是：**备份里的进度不迁移过来，本机自己的进度也不受影响**。
     *   新机本来就没有这些 key（0 次），同机重导入则原样保留。
     */
    private fun writePrefs(ctx: Context, p: Parsed) {
        for (spec in PREFS) {
            val p0 = ctx.getSharedPreferences(spec.name, Context.MODE_PRIVATE)
            val keep = if (spec.skip.isEmpty()) emptyMap() else p0.all.filterKeys { it in spec.skip }
            val e = p0.edit()
            e.clear()
            val o = p.prefMap.optJSONObject(spec.name)
            if (o != null) {
                for (k in o.keys()) {
                    if (k in spec.skip) continue
                    val box = o.optJSONObject(k) ?: continue
                    putTyped(e, k, box)
                }
            }
            // 把本机的今日进度写回去（values 为 SP 原生类型，直接放）
            for ((k, v) in keep) putRaw(e, k, v)
            // commit 而非 apply：后续立刻 reloadFromDisk，必须确保值已落盘
            e.commit()
        }
    }

    private fun putTyped(e: android.content.SharedPreferences.Editor, k: String, box: JSONObject) {
        when (box.optString("t")) {
            "b" -> e.putBoolean(k, box.optBoolean("v"))
            "i" -> e.putInt(k, box.optInt("v"))
            "l" -> e.putLong(k, box.optLong("v"))
            "f" -> e.putFloat(k, box.optDouble("v").toFloat())
            "s" -> e.putString(k, box.optString("v"))
            "ss" -> {
                val a = box.optJSONArray("v") ?: JSONArray()
                val set = LinkedHashSet<String>(a.length())
                for (i in 0 until a.length()) set.add(a.optString(i))
                e.putStringSet(k, set)
            }
        }
    }

    private fun putRaw(e: android.content.SharedPreferences.Editor, k: String, v: Any?) {
        when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Float -> e.putFloat(k, v)
            is String -> e.putString(k, v)
            is Set<*> -> {
                val set = LinkedHashSet<String>()
                for (x in v) if (x != null) set.add(x.toString())
                e.putStringSet(k, set)
            }
        }
    }

    /** 导入后的统一善后：规则下发 + 守护服务按新配置重启。 */
    private fun reapply(ctx: Context) {
        runCatching { AmsController.writeRules() }
            .onFailure { Log.w(TAG, "writeRules after import", it) }
        runCatching {
            if (AppStore.rules.any { it.block }) AmsController.ensureStarted()
        }.onFailure { Log.w(TAG, "ensureStarted after import", it) }
        runCatching { QuotaService.startIfQuotaConfigured(ctx) }
            .onFailure { Log.w(TAG, "quota service after import", it) }
        runCatching {
            AutoRelock.init(ctx)
            AutoRelock.reload(ctx)
            AutoRelockService.sync(ctx)
        }.onFailure { Log.w(TAG, "autorelock after import", it) }
        runCatching {
            if (AppStore.schedules.isNotEmpty()) ScheduleWorker.ensureScheduled(ctx)
        }.onFailure { Log.w(TAG, "schedule worker after import", it) }
    }

    // ---------------------------------------------------------------- 元信息

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(META, Context.MODE_PRIVATE)

    fun lastExportAt(ctx: Context): Long = prefs(ctx).getLong("last_export", 0L)
    fun lastImportAt(ctx: Context): Long = prefs(ctx).getLong("last_import", 0L)

    /** 由「真正把文件写出去」的调用方在成功后调用。 */
    fun markExported(ctx: Context) {
        prefs(ctx).edit().putLong("last_export", System.currentTimeMillis()).commit()
    }

    private fun markImported(ctx: Context) {
        prefs(ctx).edit().putLong("last_import", System.currentTimeMillis()).commit()
    }

    /** 当前配置的快照文本（与导出的结构完全一致，便于回滚）。 */
    private fun snapshotText(ctx: Context): String {
        val root = JSONObject()
        root.put("app", ctx.packageName)
        root.put("format", FORMAT)
        root.put("versionName", com.activitylock.app.BuildConfig.VERSION_NAME)
        root.put("exportedAt", System.currentTimeMillis())
        val files = JSONObject()
        for (spec in FILES) {
            val f = File(ctx.filesDir, spec.name)
            if (!f.exists() || f.length() == 0L) continue
            runCatching { files.put(spec.name, JSONObject(f.readText())) }
        }
        root.put("files", files)
        val prefsObj = JSONObject()
        for (spec in PREFS) {
            val all = ctx.getSharedPreferences(spec.name, Context.MODE_PRIVATE).all
            if (all.isEmpty()) continue
            val o = JSONObject()
            for ((k, value) in all) {
                if (k in spec.skip) continue
                encode(value)?.let { o.put(k, it) }
            }
            if (o.length() > 0) prefsObj.put(spec.name, o)
        }
        root.put("prefs", prefsObj)
        return root.toString()
    }
}
