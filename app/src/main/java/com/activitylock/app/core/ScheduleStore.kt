package com.activitylock.app.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalTime
import java.util.UUID

@Serializable
data class LockSchedule(
    val id: String,
    val label: String = "",
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val enabled: Boolean = true,
)

@Serializable
private class ScheduleData(val schedules: MutableList<LockSchedule> = mutableListOf())

/** 窗口状态：进窗时的上锁快照，退窗时恢复，避免覆盖用户在窗口外的自由配置。 */
@Serializable
private class WindowState(
    val inWindow: Boolean = false,
    val snapshot: Map<String, Boolean> = emptyMap(),
)

/**
 * 定时计划持久化（schedules.json）。多计划并存，任一启用计划处于时间窗内即视为"计划内"。
 * 时间窗支持跨午夜（如 23:00-07:00）。
 */
class ScheduleStore(filesDir: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(filesDir, "schedules.json")

    val schedules: List<LockSchedule> get() = _schedules.value
    private val _schedules = kotlinx.coroutines.flow.MutableStateFlow<List<LockSchedule>>(emptyList())

    init {
        runCatching {
            if (file.exists()) {
                _schedules.value = json.decodeFromString<ScheduleData>(file.readText()).schedules
            }
        }
    }

    private fun save() {
        runCatching {
            file.writeText(json.encodeToString(ScheduleData(_schedules.value.toMutableList())))
        }
    }

    fun upsert(schedule: LockSchedule) {
        val fixed = if (schedule.id.isBlank()) schedule.copy(id = UUID.randomUUID().toString()) else schedule
        _schedules.value = _schedules.value.filterNot { it.id == fixed.id } + fixed
        save()
    }

    fun remove(id: String) {
        _schedules.value = _schedules.value.filterNot { it.id == id }
        save()
    }
}

/**
 * 定时计划引擎：
 * - 进入计划窗口：保存全部规则的上锁快照 → 全部上锁 → 清栈（使拦截立即生效）
 * - 窗口内：幂等，不做任何事（用户在窗口内解锁不会被强制改回）
 * - 退出窗口：按快照恢复各规则的上锁状态
 * - 息屏清栈（可开关）：息屏时 force-stop 所有锁定中规则的包，
 *   封死"点图标恢复已有任务、不经过拦截器"的绕过路径
 */
object ScheduleEngine {

    private const val STATE_NAME = "schedule_state"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(STATE_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun screenOffKillEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("screen_off_kill", true)

    fun setScreenOffKill(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("screen_off_kill", enabled).apply()
    }

    fun inWindow(s: LockSchedule, now: LocalTime = LocalTime.now()): Boolean {
        val start = LocalTime.of(s.startHour, s.startMinute)
        val end = LocalTime.of(s.endHour, s.endMinute)
        if (start == end) return false
        return if (start < end) now >= start && now < end else now >= start || now < end
    }

    fun anyActive(schedules: List<LockSchedule>, now: LocalTime = LocalTime.now()): Boolean =
        schedules.any { it.enabled && inWindow(it, now) }

    private fun loadState(ctx: Context): WindowState =
        runCatching {
            json.decodeFromString<WindowState>(prefs(ctx).getString("state", "") ?: "")
        }.getOrDefault(WindowState())

    private fun saveState(ctx: Context, state: WindowState) {
        prefs(ctx).edit().putString("state", json.encodeToString(state)).apply()
    }

    /**
     * 对账：比较当前时刻与持久化状态，执行进窗/退窗动作。
     * 由 WorkManager 周期任务、开机广播、App 启动共同调用，幂等。
     * 返回执行的动作："enter" / "exit" / null。
     */
    fun reconcile(ctx: Context, killNow: Boolean = false): String? {
        AppStore.ensureInit(ctx)
        val store = ScheduleStore(ctx.filesDir)
        val active = anyActive(store.schedules)
        val state = loadState(ctx)
        val rules = AppStore.rules
        return when {
            active && !state.inWindow -> {
                val snapshot = rules.associate { "${it.packageName}/${it.activityName}" to it.locked }
                saveState(ctx, WindowState(inWindow = true, snapshot = snapshot))
                for (r in rules) {
                    if (!r.locked) AppStore.updateLocked(r.packageName, r.activityName, true)
                }
                AmsController.writeRules()
                killLockedPackages(ctx, rules)
                "enter"
            }
            !active && state.inWindow -> {
                for (r in rules) {
                    // 窗口期间新建的规则不在快照里，保持现状
                    state.snapshot["${r.packageName}/${r.activityName}"]?.let { was ->
                        if (was != r.locked) AppStore.updateLocked(r.packageName, r.activityName, was)
                    }
                }
                AmsController.writeRules()
                saveState(ctx, WindowState(inWindow = false, snapshot = emptyMap()))
                "exit"
            }
            else -> null
        }
    }

    /** force-stop 所有锁定中规则的包（需要 Shizuku 可用；否则静默跳过）。 */
    fun killLockedPackages(ctx: Context, rules: List<LockRule>) {
        if (rules.none { it.locked }) return
        for (pkg in rules.filter { it.locked }.map { it.packageName }.distinct()) {
            ShizukuManager.run("am", "force-stop", pkg)
        }
    }

    /** 息屏：清栈（可开关）+ 定时计划兜底对账。 */
    fun onScreenOff(ctx: Context) {
        AppStore.ensureInit(ctx)
        val rules = AppStore.rules
        if (screenOffKillEnabled(ctx)) {
            killLockedPackages(ctx, rules)
        }
        reconcile(ctx, killNow = false)
    }
}
