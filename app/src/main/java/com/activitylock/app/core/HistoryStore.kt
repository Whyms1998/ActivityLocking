package com.activitylock.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HistoryEntry(
    val packageName: String,
    val activityName: String,
    val note: String = "",
    val mode: String = "block",
    val action: String = "LOCK",
    val time: Long = System.currentTimeMillis(),
    /**
     * ★ 本次操作时该条目的跳转目标（深链/界面类名）。
     *
     * 必须落盘的原因：收藏夹删除条目后，用户会按删除确认框的指引到「锁定历史」
     * 里重新锁定；而 [HistoryEntry] 此前只记 pkg/类名/备注/模式，**不记跳转目标**，
     * 历史页的「锁定」又只调 `LockEngine.apply(pkg, cls, true)`（不带跳转）——
     * 于是「删除 → 从历史找回」必然丢跳转，表现为
     * 「重新添加后点图标不再跳搜索页，只被静默拦掉」。
     * 旧版本 history.json 没有本字段，`ignoreUnknownKeys = true` + 默认 null 可平滑兼容。
     */
    val redirectUri: String? = null,
)

@Serializable
private class HistoryData(val entries: MutableList<HistoryEntry> = mutableListOf())

/**
 * 锁定历史：所有锁定/解锁操作留痕，收藏夹删除后仍可从历史找回。
 */
class HistoryStore(filesDir: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(filesDir, "history.json")

    val entries: List<HistoryEntry> get() = _entries.value
    private val _entries = kotlinx.coroutines.flow.MutableStateFlow<List<HistoryEntry>>(emptyList())

    init {
        runCatching {
            if (file.exists()) {
                _entries.value = json.decodeFromString<HistoryData>(file.readText()).entries
            }
        }
    }

    private fun save() {
        runCatching {
            file.writeText(json.encodeToString(HistoryData(_entries.value.toMutableList())))
        }
    }

    @Synchronized
    fun add(entry: HistoryEntry) {
        val updated = listOf(entry) + _entries.value.filterNot {
            it.packageName == entry.packageName && it.activityName == entry.activityName
        }
        _entries.value = updated.take(500)
        save()
    }

    @Synchronized
    fun remove(pkg: String, cls: String) {
        _entries.value = _entries.value.filterNot {
            it.packageName == pkg && it.activityName == cls
        }
        save()
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        save()
    }
}
