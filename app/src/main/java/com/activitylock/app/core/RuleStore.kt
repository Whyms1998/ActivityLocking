package com.activitylock.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class LockRule(
    val packageName: String,
    val activityName: String,
    val note: String = "",
    val block: Boolean = false,
    val locked: Boolean = true,
    /** 拦截后跳转的深链（如搜索页 xhsdiscover://search），null 表示仅拦截不跳转。 */
    val redirectUri: String? = null,
    /** 解锁条件：null=跟随全局；"none"=无需验证；"delay"=等待；"password"=密码；"quiz"=答题 */
    val verifyMode: String? = null,
    val verifyDelaySec: Int? = null,
    val verifyPassword: String? = null,
    /** 答题需连对的题数（quiz 模式），null=跟随全局 */
    val quizCount: Int? = null,
    /** 每日使用限额（分钟），null=不限额。按 App（包名）生效，同一包取最大值。 */
    val quotaMin: Int? = null,
    /** ★ v1.30.0：每日打开次数上限，null=不限次数。按包生效，同一包取最大值。
     *  计数点 = 解锁验证通过并真正放行（LockEngine.unlock）；点图标被拦不消耗额度。 */
    val quotaCnt: Int? = null,
    /** 锁定有效期（epoch 毫秒）：到期后该规则自动失效（无需手动解锁）。0=不自动到期。 */
    val lockUntil: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
private class StoreData(
    val rules: MutableList<LockRule> = mutableListOf(),
    /** 应用级解锁条件：pkg -> 条件。未登记 = 跟随全局。 */
    val appVerify: MutableMap<String, AppVerify> = mutableMapOf(),
)

/**
 * 「应用级」解锁条件：同一个 App 下所有界面的**默认**解锁方式。
 *
 * 优先级链：**界面级（[LockRule.verifyMode]）> 应用级（本类）> 全局（[VerifyGate]）**。
 *
 * ★ 为什么需要这一层：原先只有「全局」和「单个界面」两级，一个 App 若有 5 个界面
 *   就得设 5 遍；而且批量解锁（磁贴一键解锁等）会取**所有界面的最强条件**，
 *   用户给界面 A 设了"等待 5 秒"、界面 B 忘了设，实际解锁却可能要求"输入密码"
 *   —— 界面上完全看不出按的是哪一个。加上应用级之后，一个 App 可以有一个
 *   明确的默认条件，未单独设置的界面全部跟随它。
 *
 * 字段语义与 [LockRule] 上的同名 verify* 字段完全一致：
 *  - [verifyMode] = null → 本层未设置，继续跟随全局。
 */
@Serializable
data class AppVerify(
    /** null=未设置（跟随全局）；"none"/"delay"/"password"/"quiz"/"random" */
    val verifyMode: String? = null,
    val verifyDelaySec: Int? = null,
    val verifyPassword: String? = null,
    val quizCount: Int? = null,
)

/**
 * 收藏夹（锁定清单）持久化：JSON 文件 + Compose 可观察列表。
 * block = true 表示该规则使用无障碍拦截模式（组件禁用被系统限制时）。
 */
class RuleStore(filesDir: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(filesDir, "rules.json")

    val rules: List<LockRule> get() = _rules.value
    private val _rules = kotlinx.coroutines.flow.MutableStateFlow<List<LockRule>>(emptyList())

    /** 应用级解锁条件（pkg -> 条件）。Compose 可观察。 */
    val appVerify: Map<String, AppVerify> get() = _appVerify.value
    private val _appVerify =
        kotlinx.coroutines.flow.MutableStateFlow<Map<String, AppVerify>>(emptyMap())

    init {
        runCatching {
            if (file.exists()) {
                val data = json.decodeFromString<StoreData>(file.readText())
                _rules.value = data.rules
                _appVerify.value = data.appVerify
            }
        }
    }

    private fun save() {
        runCatching {
            file.writeText(
                json.encodeToString(
                    StoreData(_rules.value.toMutableList(), _appVerify.value.toMutableMap())
                )
            )
        }
    }

    fun contains(pkg: String, cls: String) = _rules.value.any { it.packageName == pkg && it.activityName == cls }

    /**
     * ★ 全部写方法必须 @Synchronized。
     *
     * 本类会被多个线程并发调用：主线程（UI / 磁贴）、IO 线程（LockEngine.apply）、
     * 广播与 Worker 线程（定时计划巡检）。而每个写方法都是
     * 「读 _rules → 生成新列表 → 写回 + 落盘」的非原子序列：
     *  - 不加锁 → 并发时后写者覆盖先写者，表现为「刚锁的条目莫名丢了」；
     *  - 两个线程同时 file.writeText 还会交错写入，损坏 rules.json。
     * （HistoryStore / SessionStore 一直都有 @Synchronized，这里属于遗漏补齐。）
     */
    @Synchronized
    fun add(rule: LockRule) {
        if (contains(rule.packageName, rule.activityName)) return
        _rules.value = _rules.value + rule
        save()
    }

    @Synchronized
    fun remove(pkg: String, cls: String) {
        _rules.value = _rules.value.filterNot { it.packageName == pkg && it.activityName == cls }
        save()
    }

    @Synchronized
    fun updateNote(pkg: String, cls: String, note: String) {
        _rules.value = _rules.value.map {
            if (it.packageName == pkg && it.activityName == cls) it.copy(note = note.trim()) else it
        }
        save()
    }

    @Synchronized
    fun updateLocked(pkg: String, cls: String, locked: Boolean) {
        _rules.value = _rules.value.map {
            if (it.packageName == pkg && it.activityName == cls) it.copy(locked = locked) else it
        }
        save()
    }

    /**
     * 切换该规则是否使用「拦截模式」（block）。
     *
     * ★ 为什么必须有这个写方法：`AmsController.writeRules()` 只把 **block && locked**
     *   的规则下发给 Watcher。此前 `AppStore.addRule()` 在「条目已存在」分支里
     *   只更新了 locked / redirect，**完全忽略 block 参数** —— 于是一条原本由
     *   `pm disable-user` 建立的规则（block=false）被重新应用「带跳转的方案」时，
     *   跳转写进了 redirectUri，规则却因 block=false 永不 Event 下发，
     *   表现为「设置里明明有跳转，点图标就是不跳」，且方案页还会因为
     *   `locked && redirect 匹配` 而错误显示成「已启用」。
     */
    @Synchronized
    fun updateBlock(pkg: String, cls: String, block: Boolean) {
        _rules.value = _rules.value.map {
            if (it.packageName == pkg && it.activityName == cls) it.copy(block = block) else it
        }
        save()
    }

    @Synchronized
    fun updateRedirect(pkg: String, cls: String, uri: String?) {
        val clean = uri?.trim()?.takeIf { it.isNotBlank() }
        _rules.value = _rules.value.map {
            if (it.packageName == pkg && it.activityName == cls) it.copy(redirectUri = clean) else it
        }
        save()
    }

    /** 设置单条规则的解锁条件。mode=null 表示跟随全局。 */
    @Synchronized
    fun updateVerify(
        pkg: String, cls: String, mode: String?, delaySec: Int?, password: String?, quizCount: Int? = null
    ) {
        val validMode = when (mode) {
            VerifyGate.MODE_NONE, VerifyGate.METHOD_DELAY, VerifyGate.METHOD_PASSWORD, VerifyGate.METHOD_QUIZ, VerifyGate.METHOD_RANDOM -> mode
            else -> null
        }
        _rules.value = _rules.value.map {
            if (it.packageName == pkg && it.activityName == cls) it.copy(
                verifyMode = validMode,
                // ★ v1.24.9：等待时长不再限制 5–3600 秒（用户要求秒/分/小时自由输入），
                //   只保留防溢出下限 1 秒。UI 侧不再做任何 clamp。
                verifyDelaySec =
                    if (validMode == VerifyGate.METHOD_DELAY) delaySec?.let { VerifyGate.safeDelaySec(it) } else null,
                verifyPassword = if (validMode == VerifyGate.METHOD_PASSWORD) password?.trim()?.takeIf { s -> s.isNotBlank() } else null,
                quizCount = if (validMode == VerifyGate.METHOD_QUIZ) quizCount?.coerceIn(1, 999) else null,
            ) else it
        }
        save()
    }

    /**
     * 设置**应用级**解锁条件（整个 App 的默认解锁方式）。
     * mode=null 表示清除这一层，回到「跟随全局」。
     */
    @Synchronized
    fun updateAppVerify(
        pkg: String, mode: String?, delaySec: Int?, password: String?, quizCount: Int? = null
    ) {
        val validMode = when (mode) {
            VerifyGate.MODE_NONE, VerifyGate.METHOD_DELAY, VerifyGate.METHOD_PASSWORD,
            VerifyGate.METHOD_QUIZ, VerifyGate.METHOD_RANDOM -> mode
            else -> null
        }
        val next = _appVerify.value.toMutableMap()
        if (validMode == null) {
            next.remove(pkg)
        } else {
            next[pkg] = AppVerify(
                verifyMode = validMode,
                verifyDelaySec =
                    // ★ v1.24.9：与应用级/界面级同口径，只防溢出不做业务限幅
                    if (validMode == VerifyGate.METHOD_DELAY) delaySec?.let { VerifyGate.safeDelaySec(it) } else null,
                verifyPassword =
                    if (validMode == VerifyGate.METHOD_PASSWORD) password?.trim()?.takeIf { it.isNotBlank() } else null,
                quizCount =
                    if (validMode == VerifyGate.METHOD_QUIZ) quizCount?.coerceIn(1, 999) else null,
            )
        }
        _appVerify.value = next
        save()
    }

    /** 设置单条规则的锁定有效期（epoch 毫秒）。0=不自动到期。 */
    @Synchronized
    fun updateLockUntil(pkg: String, cls: String, untilMs: Long) {
        _rules.value = _rules.value.map {
            if (it.packageName == pkg && it.activityName == cls) it.copy(
                lockUntil = if (untilMs > 0) untilMs else 0
            ) else it
        }
        save()
    }

    /** ★ v1.32.0：限额已迁出到独立监督任务（SuperviseTask），清空旧字段避免双写。 */
    @Synchronized
    fun clearAllQuotaFields() {
        if (_rules.value.none { it.quotaMin != null || it.quotaCnt != null }) return
        _rules.value = _rules.value.map { it.copy(quotaMin = null, quotaCnt = null) }
        save()
    }

}
