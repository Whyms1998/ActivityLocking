package com.activitylock.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AppGroup(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
private class GroupData(
    val groups: MutableList<AppGroup> = mutableListOf(),
    /** 包名 → 分组 id；无映射 = 默认分组。 */
    val assign: MutableMap<String, String> = mutableMapOf(),
)

/**
 * 应用分组（借鉴雹的分组/标签）：工作台按分组以标签页切换。
 * 分组只影响工作台的展示与批量视角，不影响任何锁定/验证行为。
 */
class GroupStore(filesDir: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(filesDir, "groups.json")

    private val _data = kotlinx.coroutines.flow.MutableStateFlow(GroupData())

    val groups: List<AppGroup> get() = _data.value.groups
    val assignment: Map<String, String> get() = _data.value.assign

    init {
        runCatching {
            if (file.exists()) {
                _data.value = json.decodeFromString<GroupData>(file.readText())
            }
        }
    }

    private fun save() {
        runCatching { file.writeText(json.encodeToString(_data.value)) }
    }

    @Synchronized
    fun addGroup(name: String): AppGroup {
        val g = AppGroup(
            id = "g" + java.lang.Long.toString(System.currentTimeMillis(), 36),
            name = name.trim().take(12),
        )
        _data.value = GroupData(
            groups = (_data.value.groups + g).toMutableList(),
            assign = _data.value.assign,
        )
        save()
        return g
    }

    @Synchronized
    fun renameGroup(id: String, name: String) {
        val clean = name.trim().take(12)
        _data.value = GroupData(
            groups = _data.value.groups.map { g0 ->
                if (g0.id == id) AppGroup(id = g0.id, name = clean, createdAt = g0.createdAt) else g0
            }.toMutableList(),
            assign = _data.value.assign,
        )
        save()
    }

    /** 删除分组：组内应用回到默认分组。 */
    @Synchronized
    fun removeGroup(id: String) {
        _data.value = GroupData(
            groups = _data.value.groups.filterNot { g0 -> g0.id == id }.toMutableList(),
            assign = _data.value.assign.filterValues { v -> v != id }.toMutableMap(),
        )
        save()
    }

    /** 分配应用到分组；groupId = null 表示回到默认分组。 */
    @Synchronized
    fun assign(pkg: String, groupId: String?) {
        val next = _data.value.assign.toMutableMap()
        if (groupId == null) next.remove(pkg) else next[pkg] = groupId
        _data.value = GroupData(
            groups = _data.value.groups,
            assign = next.toMutableMap(),
        )
        save()
    }
}
