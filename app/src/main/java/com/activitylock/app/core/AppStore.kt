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

    /** 应用列表重试后仍为空（MIUI 偶发限制/Binder 竞态），展示手动重试入口。 */
    var appsLoadFailed by mutableStateOf(false)
        private set

    var history by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set

    var userPresets by mutableStateOf<List<ConfigPreset>>(emptyList())
        private set

    var schedules by mutableStateOf<List<LockSchedule>>(emptyList())
        private set

    var scenes by mutableStateOf<List<Scene>>(emptyList())
        private set

    var sessionCfgs by mutableStateOf<List<SessionCfg>>(emptyList())
        private set

    var groups by mutableStateOf<List<AppGroup>>(emptyList())
        private set
    var groupAssign by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** 应用级解锁条件（pkg -> 条件）。优先级：界面级 > 应用级 > 全局。 */
    var appVerify by mutableStateOf<Map<String, AppVerify>>(emptyMap())
        private set

    /**
     * ★ v1.32.0：APP 监督任务（替代原先挂在 LockRule 上的每日限额）。
     * 任务 = 一组监督对象 + 一套规则 + 生效时段，与「锁定/验证」完全解耦。
     */
    var tasks by mutableStateOf<List<SuperviseTask>>(emptyList())
        private set

    private lateinit var ruleStore: RuleStore
    private lateinit var historyStore: HistoryStore
    private lateinit var presetStore: PresetStore
    private lateinit var scheduleStore: ScheduleStore
    private lateinit var sceneStore: SceneStore
    private lateinit var sessionStore: SessionStore
    private lateinit var groupStore: GroupStore
    private lateinit var taskStore: TaskStore
    private lateinit var appContext: Context
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /**
     * ★ @Synchronized：Application.onCreate、各前台服务、Worker、磁贴都可能并发
     *   首次调用。不加锁时 `if (::ruleStore.isInitialized) return` 不是原子操作，
     *   两个线程会同时通过检查、重复构造 Store 并各自覆盖 apps/规则状态。
     */
    @Synchronized
    fun init(context: Context) {
        if (::ruleStore.isInitialized) return
        appContext = context.applicationContext
        ruleStore = RuleStore(appContext.filesDir)
        historyStore = HistoryStore(appContext.filesDir)
        presetStore = PresetStore(appContext.filesDir)
        scheduleStore = ScheduleStore(appContext.filesDir)
        sceneStore = SceneStore(appContext.filesDir)
        sessionStore = SessionStore(appContext.filesDir)
        groupStore = GroupStore(appContext.filesDir)
        taskStore = TaskStore(appContext.filesDir)
        rules = ruleStore.rules
        history = historyStore.entries
        userPresets = presetStore.presets
        schedules = scheduleStore.schedules
        scenes = sceneStore.scenes
        sessionCfgs = sessionStore.entries
        groups = groupStore.groups
        groupAssign = groupStore.assignment
        appVerify = ruleStore.appVerify
        tasks = taskStore.tasks
        migrateQuotasToTasks()
        // ★ 把「应用级解锁条件」的来源交给 VerifyGate：它的 requirementFor() 是纯静态
        //   调用点极多（磁贴 / 临时解锁 / 场景 / 收藏夹 / 自动锁闭页…），
        //   用 provider 注入可以一次性拿到三级链，无需改动全部调用点。
        VerifyGate.bindAppVerifyProvider { pkg -> appVerify[pkg] }
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
        appsLoadFailed = false
        scope.launch {
            var list = emptyList<AppEntry>()
            // 包可见性偶尔被系统收窄（MIUI Binder 竞态），指数退避重试
            for (attempt in 1..4) {
                list = withContext(Dispatchers.IO) {
                    runCatching { PmRepository.loadApps(appContext) }.getOrDefault(emptyList())
                }
                if (list.isNotEmpty()) break
                kotlinx.coroutines.delay(600L * attempt)
            }
            apps = list
            appsLoading = false
            appsLoadFailed = list.isEmpty()
        }
    }

    /** 列表明显偏少（<20）说明包可见性被系统收窄，UI 应给出引导而非当成"读取失败"。 */
    fun appsLookRestricted(): Boolean =
        !appsLoading && apps.isNotEmpty() && apps.size < 20

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
        // ★ 补初始化守卫：本文件其它方法都有，唯独这几个漏了。
        //   若在 init 之前被调用（异常时序/后台线程抢先），lateinit 访问会直接抛
        //   UninitializedPropertyAccessException 崩溃。
        if (!::ruleStore.isInitialized) return
        if (ruleStore.contains(pkg, cls)) {
            ruleStore.updateLocked(pkg, cls, true)
            // ★ 必须同步 block：writeRules() 只下发 block && locked 的规则。
            //   漏掉这里 → 「已存在但原为 pm 模式」的条目即使写入了跳转也不生效。
            ruleStore.updateBlock(pkg, cls, block)
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

    // ---------------- ★ v1.32.0：APP 监督任务 ----------------
    // 限额从「LockRule 的字段」升级为「任务」：一组监督对象（整包/单界面）+ 一套规则
    // + 生效时段。与锁定/验证彻底解耦——只限额不需要把 App 加进收藏夹。

    fun upsertTask(task: SuperviseTask) {
        if (!::taskStore.isInitialized) return
        taskStore.upsert(task)
        tasks = taskStore.tasks
        notifySuperviseChanged()
    }

    fun removeTask(id: String) {
        if (!::taskStore.isInitialized) return
        taskStore.remove(id)
        tasks = taskStore.tasks
        notifySuperviseChanged()
    }

    fun setTaskEnabled(id: String, on: Boolean) {
        if (!::taskStore.isInitialized) return
        taskStore.setEnabled(id, on)
        tasks = taskStore.tasks
        notifySuperviseChanged()
    }

    fun addTaskTarget(id: String, t: SuperviseTarget) {
        if (!::taskStore.isInitialized) return
        taskStore.addTarget(id, t)
        tasks = taskStore.tasks
        notifySuperviseChanged()
    }

    fun removeTaskTarget(id: String, key: String) {
        if (!::taskStore.isInitialized) return
        taskStore.removeTarget(id, key)
        tasks = taskStore.tasks
        notifySuperviseChanged()
    }

    /**
     * ★ v1.32.0：找出包含该监督对象的任务。
     * 先精确匹配「界面级对象」，再退到「整包对象」——工作台编辑的是具体界面，
     * 按包名整包匹配会把「微信整包任务」错显示成「朋友圈的监督」。
     */
    fun taskForTarget(pkg: String, cls: String? = null): SuperviseTask? {
        if (!::taskStore.isInitialized) return null
        val key = if (cls.isNullOrBlank()) pkg else "$pkg/$cls"
        return tasks.firstOrNull { t -> t.targets.any { it.key == key } }
            ?: if (!cls.isNullOrBlank()) {
                tasks.firstOrNull { t -> t.targets.any { it.key == pkg } }
            } else null
    }

    /**
     * 工作台就地设置某个对象的每日额度。
     *
     * 四种情况：
     *  - 该对象独占一个任务 → 直接改它的规则；**两个维度都清空 = 取消监督，任务一并删掉**
     *    （「关闭」必须写到底，不能留一个规则全空的任务挂在监督页上）；
     *  - 该对象属于「多对象任务」（一套规则管好几个 App/界面）→ 不动，返回 false
     *    （就地改会波及其他对象，必须去监督页整体调整）；
     *  - 该 App 只有整包任务在管、这个界面还没有独立任务 → 新建一个只含该界面的任务
     *    （★ v1.32.0：整包与界面是两层，整包管着也允许单独给某个界面设更严的额度）；
     *  - 完全没有任务 → 新建一个只含该对象的任务，名字用「App › 界面」。
     */
    fun setTargetQuota(pkg: String, cls: String?, limitSec: Int?, limitCnt: Int?): Boolean {
        if (!::taskStore.isInitialized) return false
        val key = if (cls.isNullOrBlank()) pkg else "$pkg/$cls"
        val clear = limitSec == null && limitCnt == null
        val own = tasks.firstOrNull { t -> t.targets.size == 1 && t.targets[0].key == key }
        if (own != null) {
            if (clear) {
                // 只清「每日时长/次数」这两个维度，别把同一个任务里的禁止使用、
                // 时段额度一起删掉；确实没有别的规则了才把空任务一并删掉
                val rest = own.rules.copy(dayLimitSec = null, dayLimitCnt = null)
                val hasOther = rest.forbid || rest.windowLimitSec != null ||
                    rest.windowLimitCnt != null || rest.runMin != null || rest.restMin != null
                if (hasOther) taskStore.upsert(own.copy(rules = rest)) else taskStore.remove(own.id)
            } else {
                taskStore.upsert(
                    own.copy(rules = own.rules.copy(dayLimitSec = limitSec, dayLimitCnt = limitCnt))
                )
            }
            tasks = taskStore.tasks
            notifySuperviseChanged()
            return true
        }
        val shared = tasks.firstOrNull { t -> t.targets.size > 1 && t.targets.any { it.key == key } }
        if (shared != null) return false
        if (clear) return true // 本来就没有独立任务，留空 = 不单独监督
        val target = SuperviseTarget(pkg, cls)
        taskStore.upsert(
            SuperviseTask(
                name = target.shortName(),
                targets = listOf(target),
                rules = SuperviseRules(dayLimitSec = limitSec, dayLimitCnt = limitCnt),
            )
        )
        tasks = taskStore.tasks
        notifySuperviseChanged()
        return true
    }

    /**
     * 监督配置变更后的统一善后：重算拦截集合 → 重写 blocked.json → 起停守护服务。
     * 顺序不能换：先算出新的拦截集合，再下发；否则会把旧的拦截写回去。
     */
    private fun notifySuperviseChanged() {
        // ★ 必须异步：tick 会做 UsageStats 全量遍历 + Shizuku 子进程，主线程调用必卡顿
        scope.launch {
            runCatching { SuperviseEngine.tick(appContext) }
            runCatching { AmsController.writeRules() }
            runCatching { QuotaService.startIfQuotaConfigured(appContext) }
        }
    }

    /**
     * ★ v1.32.0：旧数据一次性迁移 —— `LockRule.quotaMin/quotaCnt` → 监督任务。
     *
     * 旧模型把限额挂在「收藏夹的界面规则」上，导致想限时就得先锁定。
     * 这里按包聚合（多规则取最大），额度相同的包合成一个任务，额度不同则拆开，
     * 迁完清空 LockRule 上的字段，避免两套数据并存。
     */
    private fun migrateQuotasToTasks() {
        val prefs = appContext.getSharedPreferences("quota_migrate", Context.MODE_PRIVATE)
        if (prefs.getBoolean("done_v2", false)) return
        runCatching {
            val legacy = LinkedHashMap<String, Pair<Int?, Int?>>()
            for (r in ruleStore.rules) {
                if (r.quotaMin == null && r.quotaCnt == null) continue
                val cur = legacy[r.packageName]
                val m = maxOf(cur?.first ?: 0, r.quotaMin ?: 0).takeIf { it > 0 }
                val c = maxOf(cur?.second ?: 0, r.quotaCnt ?: 0).takeIf { it > 0 }
                legacy[r.packageName] = m to c
            }
            android.util.Log.i(
                "AppStore",
                "TaskMigrate: rules=${ruleStore.rules.size} legacy=${legacy.size} " +
                    "detail=" + legacy.entries.joinToString { "${it.key}=${it.value.first}/${it.value.second}" }
            )
            if (legacy.isNotEmpty()) {
                val groups = legacy.entries.groupBy { it.value }
                var i = 1
                for ((limits, entries) in groups) {
                    taskStore.addIfAbsent(
                        SuperviseTask(
                            name = if (groups.size == 1) "每日限额" else "每日限额 $i",
                            targets = entries.map { SuperviseTarget(it.key) },
                            rules = SuperviseRules(
                                dayLimitSec = limits.first?.times(60),
                                dayLimitCnt = limits.second,
                            ),
                        )
                    )
                    i++
                }
                ruleStore.clearAllQuotaFields()
                rules = ruleStore.rules
                tasks = taskStore.tasks
                android.util.Log.i(
                    "AppStore",
                    "TaskMigrate: ${legacy.size} 个包 → ${groups.size} 个监督任务"
                )
            }
            prefs.edit().putBoolean("done_v2", true).apply()
        }
    }

    /**
     * 设置**应用级**解锁条件（整个 App 的默认解锁方式）。
     * mode=null 表示清除该层、回到「跟随全局」。
     */
    fun updateAppVerify(
        pkg: String, mode: String?, delaySec: Int?, password: String?, quizCount: Int? = null
    ) {
        if (!::ruleStore.isInitialized) return
        ruleStore.updateAppVerify(pkg, mode, delaySec, password, quizCount)
        appVerify = ruleStore.appVerify
    }

    /** 该应用的应用级解锁条件（未设置为 null）。 */
    fun appVerifyOf(pkg: String): AppVerify? = appVerify[pkg]

    /** 锁定有效期（到期自动解锁），untilMs=0 表示清除。 */
    fun updateLockUntil(pkg: String, cls: String, untilMs: Long) {
        if (!::ruleStore.isInitialized) return
        ruleStore.updateLockUntil(pkg, cls, untilMs)
        rules = ruleStore.rules
    }

    // ---- 到点跳转 ----

    fun upsertSession(cfg: SessionCfg) {
        if (!::sessionStore.isInitialized) return
        sessionStore.upsert(cfg)
        sessionCfgs = sessionStore.entries
    }

    fun removeSession(pkg: String) {
        if (!::sessionStore.isInitialized) return
        sessionStore.remove(pkg)
        sessionCfgs = sessionStore.entries
    }

    // ---------------- 应用分组（借鉴雹） ----------------

    fun addGroup(name: String): AppGroup {
        val g = groupStore.addGroup(name)
        groups = groupStore.groups
        return g
    }

    fun renameGroup(id: String, name: String) {
        groupStore.renameGroup(id, name)
        groups = groupStore.groups
    }

    /** 删除分组：组内应用回到默认分组。 */
    fun removeGroup(id: String) {
        groupStore.removeGroup(id)
        groups = groupStore.groups
        groupAssign = groupStore.assignment
    }

    /** 分配应用到分组；groupId = null 表示回到默认分组。 */
    fun assignGroup(pkg: String, groupId: String?) {
        groupStore.assign(pkg, groupId)
        groupAssign = groupStore.assignment
    }

    fun groupOf(pkg: String): String? = groupAssign[pkg]

    fun setSessionEnabled(pkg: String, enabled: Boolean) {
        if (!::sessionStore.isInitialized) return
        sessionStore.setEnabled(pkg, enabled)
        sessionCfgs = sessionStore.entries
    }

    fun updateLocked(pkg: String, cls: String, locked: Boolean) {
        if (!::ruleStore.isInitialized) return
        ruleStore.updateLocked(pkg, cls, locked)
        rules = ruleStore.rules
    }

    fun hasRule(pkg: String, cls: String): Boolean =
        ::ruleStore.isInitialized && ruleStore.contains(pkg, cls)

    fun recordHistory(pkg: String, cls: String, mode: String, action: String) {
        if (!::historyStore.isInitialized) return
        val rule = rules.firstOrNull { it.packageName == pkg && it.activityName == cls }
        // ★ 跳转目标一并入历史：收藏夹删除后从历史「重新锁定」才能还原成一样的配置。
        historyStore.add(
            HistoryEntry(
                pkg, cls, rule?.note ?: "", mode, action,
                redirectUri = rule?.redirectUri,
            )
        )
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
        if (!::ruleStore.isInitialized) return
        ruleStore.remove(pkg, cls)
        rules = ruleStore.rules
    }

    fun updateNote(pkg: String, cls: String, note: String) {
        if (!::ruleStore.isInitialized) return
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
