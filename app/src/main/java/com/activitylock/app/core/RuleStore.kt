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
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
private class StoreData(val rules: MutableList<LockRule> = mutableListOf())

/**
 * 收藏夹（锁定清单）持久化：JSON 文件 + Compose 可观察列表。
 * block = true 表示该规则使用无障碍拦截模式（组件禁用被系统限制时）。
 */
class RuleStore(filesDir: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(filesDir, "rules.json")

    val rules: List<LockRule> get() = _rules.value
    private val _rules = kotlinx.coroutines.flow.MutableStateFlow<List<LockRule>>(emptyList())

    init {
        runCatching {
            if (file.exists()) {
                val data = json.decodeFromString<StoreData>(file.readText())
                _rules.value = data.rules
            }
        }
    }

    private fun save() {
        runCatching {
            file.writeText(json.encodeToString(StoreData(_rules.value.toMutableList())))
        }
    }

    fun contains(pkg: String, cls: String) = _rules.value.any { it.packageName == pkg && it.activityName == cls }

    fun add(rule: LockRule) {
        if (contains(rule.packageName, rule.activityName)) return
        _rules.value = _rules.value + rule
        save()
    }

    fun remove(pkg: String, cls: String) {
        _rules.value = _rules.value.filterNot { it.packageName == pkg && it.activityName == cls }
        save()
    }

    fun updateNote(pkg: String, cls: String, note: String) {
        _rules.value = _rules.value.map {
            if (it.packageName == pkg && it.activityName == cls) it.copy(note = note.trim()) else it
        }
        save()
    }

    fun updateLocked(pkg: String, cls: String, locked: Boolean) {
        _rules.value = _rules.value.map {
            if (it.packageName == pkg && it.activityName == cls) it.copy(locked = locked) else it
        }
        save()
    }

    fun updateRedirect(pkg: String, cls: String, uri: String?) {
        val clean = uri?.trim()?.takeIf { it.isNotBlank() }
        _rules.value = _rules.value.map {
            if (it.packageName == pkg && it.activityName == cls) it.copy(redirectUri = clean) else it
        }
        save()
    }

    /** 设置单条规则的解锁条件。mode=null 表示跟随全局。 */
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
                verifyDelaySec = if (validMode == VerifyGate.METHOD_DELAY) delaySec?.coerceIn(5, 3600) else null,
                verifyPassword = if (validMode == VerifyGate.METHOD_PASSWORD) password?.trim()?.takeIf { s -> s.isNotBlank() } else null,
                quizCount = if (validMode == VerifyGate.METHOD_QUIZ) quizCount?.coerceIn(1, 10) else null,
            ) else it
        }
        save()
    }

    /** 设置单条规则的每日限额（分钟）。min=null 表示不限额。 */
    fun updateQuota(pkg: String, cls: String, min: Int?) {
        _rules.value = _rules.value.map {
            if (it.packageName == pkg && it.activityName == cls) it.copy(
                quotaMin = min?.coerceIn(1, 1440)
            ) else it
        }
        save()
    }
}
