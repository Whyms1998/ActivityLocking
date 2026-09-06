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
}
