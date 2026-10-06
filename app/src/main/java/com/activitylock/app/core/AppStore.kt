package com.activitylock.app.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局单例：应用列表缓存 + 收藏夹规则。
 */
object AppStore {

    var rules by mutableStateOf<List<LockRule>>(emptyList())
        private set

    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set

    var appsLoading by mutableStateOf(true)
        private set

    var history by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set

    var userPresets by mutableStateOf<List<ConfigPreset>>(emptyList())
        private set

    var schedules by mutableStateOf<List<LockSchedule>>(emptyList())
        private set

    var scenes by mutableStateOf<List<Scene>>(emptyList())
        private set

    private lateinit var ruleStore: RuleStore
    private lateinit var historyStore: HistoryStore
    private lateinit var presetStore: PresetStore
    private lateinit var scheduleStore: ScheduleStore
    private lateinit var sceneStore: SceneStore
    private lateinit var appContext: Context
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    fun init(context: Context) {
        if (::ruleStore.isInitialized) return
        appContext = context.applicationContext
        ruleStore = RuleStore(appContext.filesDir)
        historyStore = HistoryStore(appContext.filesDir)
        presetStore = PresetStore(appContext.filesDir)
        scheduleStore = ScheduleStore(appContext.filesDir)
        sceneStore = SceneStore(appContext.filesDir)
        rules = ruleStore.rules
        history = historyStore.entries
        userPresets = presetStore.presets
        schedules = scheduleStore.schedules
        scenes = sceneStore.scenes
        reloadApps()
    }

    /** 方案查找：内置 + 用户方案统一入口。 */
    fun findAnyPreset(id: String): ConfigPreset? =
        BuiltinPresets.all.firstOrNull { it.id == id }
            ?: (if (::presetStore.isInitialized) presetStore.find(id) else null)

    fun ensureInit(context: Context) = init(context)

    fun context(): Context = appContext

    fun reloadApps() {
        appsLoading = true
        scope.launch {
            val list = withContext(Dispatchers.IO) { PmRepository.loadApps(appContext) }
            apps = list
            appsLoading = false
        }
    }

    fun findApp(pkg: String): AppEntry? = apps.firstOrNull { it.packageName == pkg }

    fun isDisabled(pkg: String, cls: String): Boolean =
        PmRepository.isDisabled(appContext, pkg, cls)

    fun toast(message: String) {
        android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_LONG).show()
    }

    fun copyText(text: String) {
        val cm = appContext.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("detail", text))
    }

    fun addRule(
        pkg: String,
        cls: String,
        note: String = "",
        block: Boolean = false,
        redirectUri: String? = null,
    ) {
        if (ruleStore.contains(pkg, cls)) {
            ruleStore.updateLocked(pkg, cls, true)
            // 传 null 表示不修改跳转目标；显式清空走 updateRedirect
            if (redirectUri != null) ruleStore.updateRedirect(pkg, cls, redirectUri)
        } else {
            ruleStore.add(LockRule(pkg, cls, note, block, redirectUri = redirectUri))
        }
        rules = ruleStore.rules
    }

    fun updateRedirect(pkg: String, cls: String, uri: String?) {
        if (!::ruleStore.isInitialized) return
        ruleStore.updateRedirect(pkg, cls, uri)
        rules = ruleStore.rules
    }

    fun updateVerify(
        pkg: String, cls: String, mode: String?, delaySec: Int?, password: String?, quizCount: Int? = null
    ) {
        if (!::ruleStore.isInitialized) return
        ruleStore.updateVerify(pkg, cls, mode, delaySec, password, quizCount)
        rules = ruleStore.rules
    }

    fun updateQuota(pkg: String, cls: String, min: Int?) {
        if (!::ruleStore.isInitialized) return
        ruleStore.updateQuota(pkg, cls, min)
        rules = ruleStore.rules
    }

    /** 锁定有效期（到期自动解锁），untilMs=0 表示清除。 */
    fun updateLockUntil(pkg: String, cls: String, untilMs: Long) {
        if (!::ruleStore.isInitialized) return
        ruleStore.updateLockUntil(pkg, cls, untilMs)
        rules = ruleStore.rules
    }

    fun updateLocked(pkg: String, cls: String, locked: Boolean) {
        ruleStore.updateLocked(pkg, cls, locked)
        rules = ruleStore.rules
    }

    fun hasRule(pkg: String, cls: String): Boolean =
        ::ruleStore.isInitialized && ruleStore.contains(pkg, cls)

    fun recordHistory(pkg: String, cls: String, mode: String, action: String) {
        if (!::historyStore.isInitialized) return
        val note = rules.firstOrNull { it.packageName == pkg && it.activityName == cls }?.note ?: ""
        historyStore.add(HistoryEntry(pkg, cls, note, mode, action))
        history = historyStore.entries
    }

    fun removeHistory(pkg: String, cls: String) {
        if (!::historyStore.isInitialized) return
        historyStore.remove(pkg, cls)
        history = historyStore.entries
    }

    fun clearHistory() {
        if (!::historyStore.isInitialized) return
        historyStore.clear()
        history = historyStore.entries
    }

    fun removeRule(pkg: String, cls: String) {
        ruleStore.remove(pkg, cls)
        rules = ruleStore.rules
    }

    fun updateNote(pkg: String, cls: String, note: String) {
        ruleStore.updateNote(pkg, cls, note)
        rules = ruleStore.rules
    }

    fun isAppInstalled(pkg: String): Boolean =
        runCatching { appContext.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** 一次性查询全部已安装包名，供列表批量判断（避免每行一次跨进程调用）。 */
    fun installedPackages(): Set<String> =
        runCatching {
            appContext.packageManager.getInstalledPackages(0)
                .mapNotNull { it.packageName }.toSet()
        }.getOrDefault(emptySet())

    fun upsertPreset(preset: ConfigPreset) {
        if (!::presetStore.isInitialized) return
        presetStore.upsert(preset)
        userPresets = presetStore.presets
    }

    fun removePreset(id: String) {
        if (!::presetStore.isInitialized) return
        presetStore.remove(id)
        userPresets = presetStore.presets
    }

    fun findPreset(id: String): ConfigPreset? =
        if (::presetStore.isInitialized) presetStore.find(id) else null

    /** 剪贴板 JSON 导入，返回导入条数（-1 表示格式无法识别）。 */
    fun importPresets(text: String): Int {
        if (!::presetStore.isInitialized) return -1
        val n = presetStore.import(text)
        userPresets = presetStore.presets
        return if (n == 0) -1 else n
    }

    fun exportPresetsJson(): String =
        if (::presetStore.isInitialized) presetStore.exportJson() else "[]"

    fun upsertSchedule(schedule: LockSchedule) {
        if (!::scheduleStore.isInitialized) return
        scheduleStore.upsert(schedule)
        schedules = scheduleStore.schedules
    }

    fun removeSchedule(id: String) {
        if (!::scheduleStore.isInitialized) return
        scheduleStore.remove(id)
        schedules = scheduleStore.schedules
    }

    fun upsertScene(scene: Scene) {
        if (!::sceneStore.isInitialized) return
        sceneStore.upsert(scene)
        scenes = sceneStore.scenes
    }

    fun removeScene(id: String) {
        if (!::sceneStore.isInitialized) return
        sceneStore.remove(id)
        scenes = sceneStore.scenes
    }
}
