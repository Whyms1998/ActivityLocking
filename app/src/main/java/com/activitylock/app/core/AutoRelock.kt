package com.activitylock.app.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 自动锁闭（自动重锁）配置 —— **仅全局**。
 *
 * ★ v2 简化：应用级/界面级的三层覆盖（RelockApp / RelockActivity）已整体移除。
 *   历史上为了这三层粒度累计打了十余轮补丁（静默提权、假开关、写穿、自愈…），
 *   结论是粒度本身是伪需求：
 *   - 自动锁闭的作用对象天生就是界面——解锁哪个界面就重锁哪个界面；
 *   - "要不要锁这个界面"由收藏夹的界面级锁定规则表达（不想要就别锁）；
 *   - "解锁多难"由该界面的解锁条件表达；
 *   - 在重锁链路里再做"这个界面解锁后不用重锁"的例外，与自律目标自相矛盾。
 *
 *   作用域 = 收藏夹（AppStore.rules）里全部条目。三个触发链路：
 *   ① 解锁后重新锁 ② 退出后重新锁 ③ 锁屏后重新锁，各有独立开关与延迟。
 */
/**
 * 单个界面的自动锁闭覆盖配置（v2 精简模型）。
 *
 * 与旧版三层系统的区别：**没有**逐链路参与开关、没有应用级层、没有自愈机制——
 * 只有「每条链路的延迟：跟随全局(-1) 或 自定义(>=0)」。
 * 优先级：界面级显式设置 > 全局；全局总闸关 = 一律不锁（铁律，界面级不能越过）。
 */
@Serializable
data class ActivityRelockCfg(
    val pkg: String,
    val cls: String,
    /** 解锁后重锁延迟（秒），-1 = 跟随全局。 */
    val delayUnlock: Int = -1,
    /** 退出后重锁延迟（秒），-1 = 跟随全局。退出链路按包触发，取该包内自定义的最小值。 */
    val delayExit: Int = -1,
    /** 锁屏后重锁延迟（秒），-1 = 跟随全局。 */
    val delayScreenOff: Int = -1,
) {
    fun hasOverride(): Boolean = delayUnlock >= 0 || delayExit >= 0 || delayScreenOff >= 0
}

@Serializable
data class RelockData(
    /** ① 解锁后重新锁：验证通过解锁后开始计时，到点自动重新上锁。 */
    val relockAfterUnlock: Boolean = false,
    /** ② 退出后重新锁：应用退到后台时重新上锁。 */
    val relockOnExit: Boolean = false,
    /** ③ 锁屏后重新锁：设备息屏/锁屏时重新上锁。 */
    val relockOnScreenOff: Boolean = false,
    /** 全局延迟（秒）：解锁后 / 退出后 / 锁屏后。 */
    val globalDelaySec: Int = DEFAULT_UNLOCK_DELAY_SEC,
    val globalExitDelaySec: Int = DEFAULT_EXIT_DELAY_SEC,
    val globalScreenOffDelaySec: Int = DEFAULT_SCREEN_OFF_DELAY_SEC,
    /** 总开关关闭时的快照，重新打开时原样恢复。 */
    val lastOnScenes: List<Boolean> = emptyList(),
    /** 界面级覆盖配置（仅显式自定义过延迟的界面）。 */
    val activities: List<ActivityRelockCfg> = emptyList(),
    val schema: Int = CURRENT_SCHEMA,
) {
    companion object {
        /** ①「解锁后」默认 5 分钟：验证一次，这段时间内随便切。 */
        const val DEFAULT_UNLOCK_DELAY_SEC = 300

        /** ②「退出后」默认 5 分钟。 */
        const val DEFAULT_EXIT_DELAY_SEC = 300

        /** ③「锁屏后」默认 0 秒 = 立即。 */
        const val DEFAULT_SCREEN_OFF_DELAY_SEC = 0

        /** v1.x 旧统一默认值（只用于迁移比对）。 */
        const val LEGACY_DEFAULT_DELAY_SEC = 0

        const val MAX_DELAY_SEC = 100_000_000

        const val NOT_LOCK = -1
        const val CURRENT_SCHEMA = 5
        const val DELAY_UNLOCK = 0
        const val DELAY_EXIT = 1
        const val DELAY_SCREEN_OFF = 2
        const val LINK_UNLOCK = 0
        const val LINK_EXIT = 1
        const val LINK_SCREEN_OFF = 2
    }
}

class AutoRelockStore(filesDir: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(filesDir, "relock.json")

    private val _data = kotlinx.coroutines.flow.MutableStateFlow(RelockData())

    val relockAfterUnlockEnabled: Boolean get() = _data.value.relockAfterUnlock
    val relockOnExitEnabled: Boolean get() = _data.value.relockOnExit
    val relockOnScreenOffEnabled: Boolean get() = _data.value.relockOnScreenOff
    val globalDelaySec: Int get() = _data.value.globalDelaySec
    val globalExitDelaySec: Int get() = _data.value.globalExitDelaySec
    val globalScreenOffDelaySec: Int get() = _data.value.globalScreenOffDelaySec

    /** 任一子开关打开即视为「自动锁闭」已启用。 */
    val enabled: Boolean
        get() = relockAfterUnlockEnabled || relockOnExitEnabled || relockOnScreenOffEnabled

    init {
        runCatching {
            if (file.exists()) {
                val loaded = json.decodeFromString<RelockData>(file.readText())
                _data.value = migrate(loaded)
                if (loaded.schema != RelockData.CURRENT_SCHEMA) save()
            } else {
                save()
            }
        }
    }

    /**
     * 迁移：schema < 4 的旧数据带 apps/activities 三层名单，
     * 新模型直接丢弃（ignoreUnknownKeys 负责读入时忽略旧字段）；
     * 全局延迟沿用设计文档默认（解锁后 5 分钟 / 退出后 5 分钟 / 锁屏后立即），
     * 旧数据里全局层本来就是独立字段，无需转换。
     */
    private fun migrate(d: RelockData): RelockData {
        if (d.schema >= RelockData.CURRENT_SCHEMA) return d
        return d.copy(schema = RelockData.CURRENT_SCHEMA)
    }

    private fun save() {
        runCatching { file.writeText(json.encodeToString(_data.value)) }
    }

    @Synchronized
    fun setRelockAfterUnlockEnabled(v: Boolean) {
        _data.value = _data.value.copy(relockAfterUnlock = v); save()
    }

    @Synchronized
    fun setRelockOnExitEnabled(v: Boolean) {
        _data.value = _data.value.copy(relockOnExit = v); save()
    }

    @Synchronized
    fun setRelockOnScreenOffEnabled(v: Boolean) {
        _data.value = _data.value.copy(relockOnScreenOff = v); save()
    }

    /** 顶部总开关：关 = 三条一起关（存快照）；开 = 原样恢复，无可恢复则三条全开。 */
    @Synchronized
    fun setAllLinks(enabled: Boolean) {
        val d = _data.value
        if (!enabled) {
            _data.value = d.copy(
                relockAfterUnlock = false,
                relockOnExit = false,
                relockOnScreenOff = false,
                lastOnScenes = listOf(d.relockAfterUnlock, d.relockOnExit, d.relockOnScreenOff),
            )
        } else {
            val snap = d.lastOnScenes
            val restore = snap.size == 3 && snap.any { it }
            _data.value = d.copy(
                relockAfterUnlock = if (restore) snap[0] else true,
                relockOnExit = if (restore) snap[1] else true,
                relockOnScreenOff = if (restore) snap[2] else true,
            )
        }
        save()
    }

    @Synchronized
    fun setGlobalDelaySec(sec: Int) {
        _data.value = _data.value.copy(globalDelaySec = sec.coerceIn(0, RelockData.MAX_DELAY_SEC)); save()
    }

    @Synchronized
    fun setGlobalExitDelaySec(sec: Int) {
        _data.value = _data.value.copy(globalExitDelaySec = sec.coerceIn(0, RelockData.MAX_DELAY_SEC)); save()
    }

    @Synchronized
    fun setGlobalScreenOffDelaySec(sec: Int) {
        _data.value = _data.value.copy(globalScreenOffDelaySec = sec.coerceIn(0, RelockData.MAX_DELAY_SEC)); save()
    }

    // ---------------- 界面级覆盖 ----------------

    val activityCfgs: List<ActivityRelockCfg> get() = _data.value.activities

    fun activityCfg(pkg: String?, cls: String): ActivityRelockCfg? =
        if (pkg == null) null
        else _data.value.activities.firstOrNull { it.pkg == pkg && it.cls == cls }

    @Synchronized
    fun setActivityCfg(cfg: ActivityRelockCfg) {
        val next = _data.value.activities.filterNot { it.pkg == cfg.pkg && it.cls == cfg.cls }
        _data.value = _data.value.copy(
            activities = if (cfg.hasOverride()) next + cfg else next,
            schema = RelockData.CURRENT_SCHEMA,
        )
        save()
    }

    /** 指定链路的生效延迟（全局层，pkg/cls 参数仅为兼容旧调用签名）。 */
    fun resolveLockSec(pkg: String?, cls: String?, link: Int): Int {
        val on = when (link) {
            AutoRelockStore.LINK_EXIT -> relockOnExitEnabled
            AutoRelockStore.LINK_SCREEN_OFF -> relockOnScreenOffEnabled
            else -> relockAfterUnlockEnabled
        }
        if (!on) return AutoRelockStore.NOT_LOCK
        return when (link) {
            AutoRelockStore.LINK_EXIT -> globalExitDelaySec
            AutoRelockStore.LINK_SCREEN_OFF -> globalScreenOffDelaySec
            else -> globalDelaySec
        }
    }

    fun isEnabledFor(pkg: String?, cls: String?, link: Int): Boolean =
        resolveLockSec(pkg, cls, link) >= 0

    fun isEnabledForAny(pkg: String?, cls: String?): Boolean =
        isEnabledFor(pkg, cls, AutoRelockStore.LINK_UNLOCK) ||
            isEnabledFor(pkg, cls, AutoRelockStore.LINK_EXIT) ||
            isEnabledFor(pkg, cls, AutoRelockStore.LINK_SCREEN_OFF)

    companion object {
        const val MAX_DELAY_SEC = 100_000_000
        const val NOT_LOCK = -1
        const val CURRENT_SCHEMA = 5
        const val DELAY_UNLOCK = 0
        const val DELAY_EXIT = 1
        const val DELAY_SCREEN_OFF = 2
        const val LINK_UNLOCK = 0
        const val LINK_EXIT = 1
        const val LINK_SCREEN_OFF = 2
    }
}

/** 全局单例：自动锁闭状态 + 重新上锁执行。 */
object AutoRelock {

    private var storeRef: AutoRelockStore? = null
    private lateinit var ctx: Context

    var relockAfterUnlockEnabled by mutableStateOf(false)
        private set
    var relockOnExitEnabled by mutableStateOf(false)
        private set
    var relockOnScreenOffEnabled by mutableStateOf(false)
        private set
    var globalDelaySec by mutableStateOf(RelockData.DEFAULT_UNLOCK_DELAY_SEC)
        private set
    var globalExitDelaySec by mutableStateOf(RelockData.DEFAULT_EXIT_DELAY_SEC)
        private set
    var globalScreenOffDelaySec by mutableStateOf(RelockData.DEFAULT_SCREEN_OFF_DELAY_SEC)
        private set

    /** 当前处于「已解锁但待重新锁」状态的界面：key = "pkg/cls" → 解锁时刻。 */
    var pendingRelock by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    fun init(context: Context) {
        if (storeRef != null) return
        ctx = context.applicationContext
        storeRef = AutoRelockStore(ctx.filesDir)
        refresh()
    }

    private val store: AutoRelockStore get() = storeRef ?: error("AutoRelock 未初始化")

    private fun refresh() {
        relockAfterUnlockEnabled = store.relockAfterUnlockEnabled
        relockOnExitEnabled = store.relockOnExitEnabled
        relockOnScreenOffEnabled = store.relockOnScreenOffEnabled
        globalDelaySec = store.globalDelaySec
        globalExitDelaySec = store.globalExitDelaySec
        globalScreenOffDelaySec = store.globalScreenOffDelaySec
    }

    fun store(): AutoRelockStore = store

    // ---------------- 配置写入 ----------------

    fun setRelockAfterUnlock(v: Boolean) {
        store.setRelockAfterUnlockEnabled(v)
        if (!v) pendingRelock = emptyMap()
        refresh()
    }

    fun setRelockOnExit(v: Boolean) {
        store.setRelockOnExitEnabled(v)
        if (!v) pendingRelock = emptyMap()
        refresh()
    }

    fun setRelockOnScreenOff(v: Boolean) {
        store.setRelockOnScreenOffEnabled(v)
        if (!v) pendingRelock = emptyMap()
        refresh()
    }

    fun setAllLinks(enabled: Boolean) {
        store.setAllLinks(enabled)
        if (!enabled) pendingRelock = emptyMap()
        refresh()
    }

    fun setGlobalDelay(sec: Int) { store.setGlobalDelaySec(sec); refresh() }
    fun setGlobalExitDelay(sec: Int) { store.setGlobalExitDelaySec(sec); refresh() }
    fun setGlobalScreenOffDelay(sec: Int) { store.setGlobalScreenOffDelaySec(sec); refresh() }

    val anyLinkEnabled: Boolean
        get() = relockAfterUnlockEnabled || relockOnExitEnabled || relockOnScreenOffEnabled

    /**
     * 生效延迟：界面级显式自定义 > 全局。
     * 退出链路按包触发（cls=null）：取该包内界面自定义的最小值（更早锁 = 更强）。
     */
    private fun resolveDelay(pkg: String?, cls: String?, link: Int): Int {
        val global = when (link) {
            AutoRelockStore.LINK_EXIT -> globalExitDelaySec
            AutoRelockStore.LINK_SCREEN_OFF -> globalScreenOffDelaySec
            else -> globalDelaySec
        }
        if (cls != null) {
            val c = store.activityCfg(pkg, cls)
            if (c != null) {
                val v = when (link) {
                    AutoRelockStore.LINK_EXIT -> c.delayExit
                    AutoRelockStore.LINK_SCREEN_OFF -> c.delayScreenOff
                    else -> c.delayUnlock
                }
                if (v >= 0) return v
            }
        } else if (link == AutoRelockStore.LINK_EXIT) {
            val customs = store.activityCfgs
                .filter { it.pkg == pkg && it.delayExit >= 0 }
                .map { it.delayExit }
            if (customs.isNotEmpty()) return customs.min()
        }
        return global
    }

    fun effectiveDelaySec(pkg: String?, cls: String?): Int =
        resolveDelay(pkg, cls, AutoRelockStore.LINK_UNLOCK)

    fun effectiveExitDelaySec(pkg: String?, cls: String?): Int =
        resolveDelay(pkg, cls, AutoRelockStore.LINK_EXIT)

    fun effectiveScreenOffDelaySec(pkg: String?, cls: String?): Int =
        resolveDelay(pkg, cls, AutoRelockStore.LINK_SCREEN_OFF)

    fun activityCfg(pkg: String, cls: String): ActivityRelockCfg? =
        if (storeRef == null) null else store.activityCfg(pkg, cls)

    fun setActivityCfg(cfg: ActivityRelockCfg) {
        store.setActivityCfg(cfg)
        refresh()
    }

    /** 待重新锁的界面数（UI 徽标）。 */
    val pendingCount: Int get() = pendingRelock.size

    // ---------------- 解锁埋点 ----------------

    /** 记录「某界面刚被解锁」——服务据此开始计时。 */
    fun noteUnlocked(pkg: String, cls: String?) {
        if (!relockAfterUnlockEnabled) return
        if (!isTracked(pkg, cls)) return
        pendingRelock = pendingRelock + (keyOf(pkg, cls) to android.os.SystemClock.elapsedRealtime())
    }

    /**
     * 是否受自动锁闭管辖：收藏夹里有该条目，且对应链路的全局开关打开。
     * 作用域 = 收藏夹全部条目（v2 起不再有名单覆盖）。
     */
    fun isTracked(pkg: String, cls: String?, link: Int = AutoRelockStore.LINK_UNLOCK): Boolean {
        val inFavorites = AppStore.rules.any {
            it.packageName == pkg && (cls == null || it.activityName == cls)
        }
        if (!inFavorites) return false
        return store.isEnabledFor(pkg, cls, link)
    }

    fun isTrackedAny(pkg: String, cls: String?): Boolean {
        val inFavorites = AppStore.rules.any {
            it.packageName == pkg && (cls == null || it.activityName == cls)
        }
        if (!inFavorites) return false
        return store.isEnabledForAny(pkg, cls)
    }

    fun noteOpened(pkg: String, cls: String?) {
        val key = keyOf(pkg, cls)
        if (pendingRelock.containsKey(key)) pendingRelock = pendingRelock - key
    }

    fun clearPending() { pendingRelock = emptyMap() }

    fun clearPendingKey(key: String) {
        if (pendingRelock.containsKey(key)) pendingRelock = pendingRelock - key
    }

    private fun keyOf(pkg: String, cls: String?): String = "$pkg/${cls ?: "*"}"

    fun parseKey(key: String): Pair<String, String?> {
        val i = key.indexOf('/')
        if (i < 0) return key to null
        val p = key.substring(0, i)
        val c = key.substring(i + 1)
        return p to if (c == "*") null else c
    }

    // ---------------- 重新上锁执行 ----------------

    /**
     * 重新上锁。走 [LockEngine] —— 与手动上锁同一条路径。
     * `cls` 为 null 时锁回该包下全部尚未锁定的条目（退出链路形态）。
     */
    suspend fun relockOne(pkg: String, cls: String?, link: Int = AutoRelockStore.LINK_UNLOCK): Boolean {
        val rules = AppStore.rules
        val targets = if (cls != null) {
            rules.filter { it.packageName == pkg && it.activityName == cls }
        } else {
            rules.filter { it.packageName == pkg && !it.locked }
        }
        if (targets.isEmpty()) return false
        var any = false
        for (r in targets) {
            if (LockEngine.apply(r.packageName, r.activityName, lock = true).locked) any = true
        }
        if (any) pendingRelock = pendingRelock - keyOf(pkg, cls)
        return any
    }

    /** 把所有待锁界面立即重新锁上。返回成功数。 */
    suspend fun relockAllPending(): Int {
        val keys = pendingRelock.keys.toList()
        var n = 0
        for (k in keys) {
            val (p, c) = parseKey(k)
            if (relockOne(p, c)) n++
        }
        return n
    }

    /** 把收藏夹里所有当前处于解锁态的界面重新锁上（磁贴/紧急用）。返回成功数。 */
    suspend fun relockAllListed(): Int {
        var n = 0
        for (rule in AppStore.rules.toList()) {
            if (rule.locked) continue
            if (relockOne(rule.packageName, rule.activityName)) n++
        }
        return n
    }

    /** 是否处于「守护中」：任一开关打开，且收藏夹里至少有一个条目。 */
    fun active(): Boolean = storeRef?.let { it.enabled && hasTargets } ?: false

    /** 收藏夹里当前处于锁定态且受管辖的界面数。 */
    fun lockedTargetCount(): Int =
        AppStore.rules.count { it.locked && isTrackedAny(it.packageName, it.activityName) }

    /** 收藏夹里当前处于解锁态的界面数（磁贴「一键锁闭」可锁回的数量）。 */
    fun countUnlockedListed(): Int = AppStore.rules.count { !it.locked }

    /** 参与自动锁闭的界面总数（= 已锁定条目数，Compose 可观察）。 */
    val targetCount: Int
        get() = AppStore.rules.count { it.locked }

    val hasTargets: Boolean
        get() = AppStore.rules.any { it.locked }
}
