package com.activitylock.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
private class PresetData(val presets: MutableList<ConfigPreset> = mutableListOf())

/**
 * 用户自定义方案持久化（presets.json）：自建、修改、删除、剪贴板导入导出。
 * 内置方案（BuiltinPresets）不在此存储；导入的外来方案一律转为用户方案。
 */
class PresetStore(filesDir: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(filesDir, "presets.json")

    val presets: List<ConfigPreset> get() = _presets.value
    private val _presets = kotlinx.coroutines.flow.MutableStateFlow<List<ConfigPreset>>(emptyList())

    init {
        runCatching {
            if (file.exists()) {
                _presets.value = json.decodeFromString<PresetData>(file.readText()).presets
            }
        }
    }

    private fun save() {
        runCatching {
            file.writeText(json.encodeToString(PresetData(_presets.value.toMutableList())))
        }
    }

    fun upsert(preset: ConfigPreset) {
        val fixed = if (preset.id.isBlank()) preset.copy(id = UUID.randomUUID().toString()) else preset
        _presets.value =
            _presets.value.filterNot { it.id == fixed.id } + fixed
        save()
    }

    fun remove(id: String) {
        _presets.value = _presets.value.filterNot { it.id == id }
        save()
    }

    fun find(id: String): ConfigPreset? = _presets.value.firstOrNull { it.id == id }

    /** 导出全部用户方案为 JSON 文本（内置方案不导出，App 自带）。 */
    fun exportJson(): String = json.encodeToString(PresetData(_presets.value.toMutableList()))

    /**
     * 从 JSON 文本导入：兼容 数组 / 单对象 / {"presets":[...]} 三种形态。
     * 外来方案一律转为用户方案；id 相同视为同一条并覆盖。返回导入条数。
     */
    fun import(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        val incoming: List<ConfigPreset> = runCatching {
            json.decodeFromString<List<ConfigPreset>>(trimmed)
        }.getOrElse {
            runCatching {
                listOf(json.decodeFromString<ConfigPreset>(trimmed))
            }.getOrElse {
                runCatching {
                    json.decodeFromString<PresetData>(trimmed).presets
                }.getOrDefault(emptyList())
            }
        }
        if (incoming.isEmpty()) return 0
        var count = 0
        for (p in incoming) {
            if (p.packageName.isBlank() || p.activityName.isBlank()) continue
            upsert(
                p.copy(
                    id = p.id.ifBlank { UUID.randomUUID().toString() },
                    builtin = false,
                    // 外来方案不继承"已验证"标记，本机表现需自行测试
                    verified = false,
                )
            )
            count++
        }
        return count
    }
}
