package com.activitylock.app.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 场景应用集：一组方案的命名集合，一键启用/停用。
 * 启用 = 逐个应用成员方案（锁定+跳转）；停用 = 成员 App 全部解锁（可自由使用）。
 * 典型用法：「工作模式」锁全部信息流；「休息模式」一键放开。
 */
@Serializable
data class Scene(
    val id: String,
    val label: String = "",
    val presetIds: List<String> = emptyList(),
    val active: Boolean = false,
)

@Serializable
private class SceneData(val scenes: MutableList<Scene> = mutableListOf())

class SceneStore(filesDir: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(filesDir, "scenes.json")

    val scenes: List<Scene> get() = _scenes.value
    private val _scenes = kotlinx.coroutines.flow.MutableStateFlow<List<Scene>>(emptyList())

    init {
        runCatching {
            if (file.exists()) {
                _scenes.value = json.decodeFromString<SceneData>(file.readText()).scenes
            }
        }
    }

    private fun save() {
        runCatching {
            file.writeText(json.encodeToString(SceneData(_scenes.value.toMutableList())))
        }
    }

    fun upsert(scene: Scene) {
        val fixed = if (scene.id.isBlank()) scene.copy(id = UUID.randomUUID().toString()) else scene
        _scenes.value = _scenes.value.filterNot { it.id == fixed.id } + fixed
        save()
    }

    fun remove(id: String) {
        _scenes.value = _scenes.value.filterNot { it.id == id }
        save()
    }
}

object SceneEngine {

    /** 成员方案对应的包名集合（方案已删除的成员跳过）。 */
    fun memberPackages(scene: Scene): Set<String> =
        scene.presetIds.mapNotNull { AppStore.findAnyPreset(it)?.packageName }.toSet()

    /** 启用场景：逐个应用成员方案（锁定+跳转）。返回成功条数。 */
    suspend fun activate(scene: Scene): Int {
        var n = 0
        for (pid in scene.presetIds) {
            val p = AppStore.findAnyPreset(pid) ?: continue
            runCatching {
                if (p.redirectUri.isNullOrBlank()) {
                    AppStore.updateRedirect(p.packageName, p.activityName, null)
                }
            }
            val o = LockEngine.apply(p.packageName, p.activityName, true, p.redirectUri)
            if (o.locked) {
                if (p.note.isNotBlank()) AppStore.updateNote(p.packageName, p.activityName, p.note)
                n++
            }
        }
        AmsController.writeRules()
        return n
    }

    /** 停用场景：成员 App 全部解锁（它们将可自由使用）。返回解锁条数。 */
    suspend fun deactivate(scene: Scene): Int {
        var n = 0
        for (pid in scene.presetIds) {
            val p = AppStore.findAnyPreset(pid) ?: continue
            val rules = AppStore.rules.filter {
                it.packageName == p.packageName && it.locked
            }
            for (r in rules) {
                LockEngine.apply(r.packageName, r.activityName, false)
                n++
            }
        }
        AmsController.writeRules()
        return n
    }

    /** 停用场景需要通过的验证：成员规则的最强解锁条件。 */
    fun deactivateRequirement(ctx: Context, scene: Scene): VerifyGate.Requirement {
        val memberRules = AppStore.rules.filter {
            it.packageName in memberPackages(scene) && it.locked
        }
        return VerifyGate.bulkRequirement(ctx, memberRules)
    }
}
