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
        else -> activityShortName(cls)
    }
    return "$app › $simple"
}

/**
 * 界面类名 → 可读短名（★ v1.32.2）。
 *
 * 没有人工标注时，通用入口类名统一叫「首页」——否则去掉 Activity 后缀会剩下
 * "Main"、"Launcher" 这种残词，比类名本身还难懂（真机显示成「酷安 › Main」）。
 */
fun activityShortName(cls: String): String {
    val raw = cls.substringAfterLast('.')
        .removeSuffix("Activity").removeSuffix("UI").removeSuffix("Ui")
    return if (raw in COMMON_ENTRY_NAMES) "首页" else raw
}

/** 各家「首页 / 入口」的常见类名残词（去掉 Activity 后缀之后）。 */
private val COMMON_ENTRY_NAMES = setOf(
    "Main", "Launcher", "Splash", "Index", "Welcome", "Home", "Entry", "Start"
)

/**
 * 读数封顶：超额之后**不再显示真实增量**。
 *
 * 原因：达标后每次点图标都会产生一次「打开尝试」，UsageStats 里就是一次新会话，
 * 读数会一路涨到 98/3、200/5 这种数字（真机实测）。对用户来说这个数字没有信息量
 * （反正已经进不去了），只会让人怀疑统计坏了。统一封顶到上限值。
 *
 * 注意：只影响**展示**，引擎判定与拦截完全不受影响（判定用的是 >=，封顶前后结论一致）。
 */
fun cappedUsed(used: Long, limit: Long): Long =
    if (limit > 0 && used > limit) limit else used

fun cappedUsed(used: Int, limit: Int): Int =
    if (limit > 0 && used > limit) limit else used

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
 * 削弱判定（★ v1.33.0）：新任务配置是否比旧任务更宽松。
 *
 * 「调大额度 / 清除限制 / 关掉禁止 / 摘掉对象 / 缩短生效时段 / 关任务」都算削弱——
 * 与自动锁闭「关闭、调大延迟需先验证」同一个口径：放宽约束必须先过验证，
 * 收紧（新增限制、调小额度、开任务、加对象）不拦。old 为 null（新建任务）不算。
 */
fun superviseWeakened(old: SuperviseTask?, new: SuperviseTask): Boolean {
    if (old == null) return false
    if (old.enabled && !new.enabled) return true
    if (!old.enabled) return false
    if (old.rules.forbid && !new.rules.forbid) return true
    if (loosened(old.rules.windowLimitSec, new.rules.windowLimitSec)) return true
    if (loosened(old.rules.windowLimitCnt, new.rules.windowLimitCnt)) return true
    if (loosened(old.rules.dayLimitSec, new.rules.dayLimitSec)) return true
    if (loosened(old.rules.dayLimitCnt, new.rules.dayLimitCnt)) return true
    if (old.targets.any { o -> new.targets.none { it.key == o.key } }) return true
    // 生效时段缩短（老时段有新时段覆盖不到的分钟）= 每天有一段没人管
    for (minute in 0 until 1440) {
        if (old.inWindow(minute) && !new.inWindow(minute)) return true
    }
    return false
}

/** 有限制 → 无限制或更大 = 放宽；无限制 → 有限制 = 收紧（不算）。 */
private fun loosened(old: Int?, new: Int?): Boolean =
    old != null && (new == null || new > old)

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
