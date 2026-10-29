package com.activitylock.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 监督对象：整包（cls=null）或单个界面（cls=非空）。
 *
 * ★ v1.32.0：额度不再挂在 LockRule 上，也不再只按 App 统计。
 *   真实需求是「微信正常用，只限朋友圈」——同一任务里可以混放整包与单界面，
 *   每个对象各自结算额度。
 *
 * 拦截通路：
 *  - 整包 → watcher 包级条目 {"p":pkg}（与是否在收藏夹无关）
 *  - 单界面 → watcher 组件条目 {"c":"pkg/cls"}
 */
@Serializable
data class SuperviseTarget(
    val pkg: String,
    val cls: String? = null,
) {
    val isWholeApp: Boolean get() = cls.isNullOrBlank()
    val key: String get() = if (isWholeApp) pkg else "$pkg/$cls"

    /** 展示用：优先取应用里的中文界面名，退回类名。 */
    fun shortName(): String = superviseTargetLabel(pkg, cls)
}

/**
 * 监督对象的展示名：「App 名」或「App › 界面名」。
 *
 * 界面中文名只有一个来源可用 —— 内置预设（[BuiltinPresets]）里人工标注过的
 * (包名, 界面) → 标签（例如 「微信朋友圈」）。PmRepository 扫到的是类名
 * （ImproveSnsTimelineUI），直接显示完全不可读。
 */
fun superviseTargetLabel(pkg: String, cls: String?): String {
    val app = AppStore.findApp(pkg)?.label ?: pkg.substringAfterLast('.')
    if (cls.isNullOrBlank()) return app
    val preset = BuiltinPresets.all
        .firstOrNull { it.packageName == pkg && it.activityName == cls }?.label
    val simple = when {
        preset != null && preset.length > app.length && preset.startsWith(app) ->
            preset.removePrefix(app)
        preset != null && preset != app -> preset
        else -> cls.substringAfterLast('.')
            .removeSuffix("Activity").removeSuffix("UI").removeSuffix("Ui")
    }
    return "$app › $simple"
}

/**
 * 监督规则集：6 类约束，可任意组合。任一项非空/为真即视为启用。
 *
 * 时长一律存**秒**（输入是分钟/小时，存秒避免取整丢失）；次数存次。
 * 每个 [SuperviseTarget] 独立结算这套规则。
 */
@Serializable
data class SuperviseRules(
    /** 禁止使用：生效时段内一律拦。 */
    val forbid: Boolean = false,
    /** 连续使用 N 分钟（P2 启用，一期仅落库不生效）。 */
    val runMin: Int? = null,
    /** 连续使用后休息 M 分钟（P2）。 */
    val restMin: Int? = null,
    /** 当前时间段内累计可用秒数。 */
    val windowLimitSec: Int? = null,
    /** 当前时间段内最多打开次数。 */
    val windowLimitCnt: Int? = null,
    /** 当天累计可用秒数。 */
    val dayLimitSec: Int? = null,
    /** 当天最多打开次数。 */
    val dayLimitCnt: Int? = null,
) {
    /** 是否配了任何一条一期可执行的约束（连续使用休息属 P2，不算）。 */
    val active: Boolean
        get() = forbid || windowLimitSec != null || windowLimitCnt != null ||
            dayLimitSec != null || dayLimitCnt != null
}

/**
 * 监督任务：一组对象 + 一套规则 + 生效时段。
 *
 * 与「锁定（收藏夹）」是**两层**：锁定管「进这个界面要验证」，
 * 监督管「今天这个对象总共能用多久/几次」。同一对象可同时被两层管。
 */
@Serializable
data class SuperviseTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val group: String = "",
    val enabled: Boolean = true,
    /** 生效时段起：当天第几分钟（0 = 00:00）。 */
    val windowStart: Int = 0,
    /** 生效时段止：当天第几分钟（1439 = 23:59）。start > end 表示跨夜。 */
    val windowEnd: Int = 1439,
    val targets: List<SuperviseTarget> = emptyList(),
    val rules: SuperviseRules = SuperviseRules(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** 该任务当前是否可能产生拦截（开关 + 有规则 + 有对象 + 在时段内）。 */
    fun effectiveAt(nowMinute: Int): Boolean =
        enabled && rules.active && targets.isNotEmpty() && inWindow(nowMinute)

    fun inWindow(nowMinute: Int): Boolean =
        if (windowStart <= windowEnd) nowMinute in windowStart..windowEnd
        else nowMinute >= windowStart || nowMinute <= windowEnd

    /** 时段标签，如「08:00–23:00」「全天」。 */
    fun windowLabel(): String {
        if (windowStart == 0 && windowEnd == 1439) return "全天"
        fun fmt(m: Int) = "%02d:%02d".format(m / 60, m % 60)
        return "${fmt(windowStart)}–${fmt(windowEnd)}"
    }
}

@Serializable
private class TaskData(val tasks: MutableList<SuperviseTask> = mutableListOf())

/**
 * 监督任务持久化（supervise_tasks.json）。
 * 与 RuleStore 同构：MutableStateFlow + JSON 落盘，全部写方法 @Synchronized。
 */
class TaskStore(filesDir: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(filesDir, "supervise_tasks.json")

    private val _tasks = MutableStateFlow<List<SuperviseTask>>(emptyList())
    val tasks: List<SuperviseTask> get() = _tasks.value

    init {
        runCatching {
            if (file.exists()) {
                _tasks.value = json.decodeFromString<TaskData>(file.readText()).tasks
            }
        }
    }

    private fun save() {
        runCatching {
            file.writeText(json.encodeToString(TaskData(_tasks.value.toMutableList())))
        }
    }

    @Synchronized
    fun upsert(task: SuperviseTask) {
        val exists = _tasks.value.any { it.id == task.id }
        _tasks.value =
            if (exists) _tasks.value.map { if (it.id == task.id) task else it }
            else _tasks.value + task
        save()
    }

    @Synchronized
    fun remove(id: String) {
        _tasks.value = _tasks.value.filterNot { it.id == id }
        save()
    }

    @Synchronized
    fun setEnabled(id: String, on: Boolean) {
        _tasks.value = _tasks.value.map { if (it.id == id) it.copy(enabled = on) else it }
        save()
    }

    @Synchronized
    fun addTarget(id: String, t: SuperviseTarget) {
        _tasks.value = _tasks.value.map {
            if (it.id == id) {
                if (it.targets.any { x -> x.key == t.key }) it
                else it.copy(targets = it.targets + t)
            } else it
        }
        save()
    }

    @Synchronized
    fun removeTarget(id: String, key: String) {
        _tasks.value = _tasks.value.map {
            if (it.id == id) it.copy(targets = it.targets.filterNot { x -> x.key == key }) else it
        }
        save()
    }

    /** 迁移用：只填空位，不覆盖已有任务。 */
    @Synchronized
    fun addIfAbsent(task: SuperviseTask) {
        if (_tasks.value.any { it.id == task.id || it.name == task.name }) return
        _tasks.value = _tasks.value + task
        save()
    }
}
