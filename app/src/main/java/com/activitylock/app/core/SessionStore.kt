package com.activitylock.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SessionCfg(
    /** 触发 App：连续使用达到时限后跳走。 */
    val pkg: String,
    /** 跳转目标 App：包名（启动其主界面），由 App 侧解析成组件名后下发给 Watcher。 */
    val target: String,
    /** 连续使用多少分钟后触发。 */
    val limitMin: Int = 1,
    /** 当日第 N 次跳转后锁定该 App（0=不启用，次日自动恢复）。 */
    val maxJumps: Int = 3,
    val enabled: Boolean = true,
)

@Serializable
private class SessionData(val items: MutableList<SessionCfg> = mutableListOf())

/**
 * 到点跳转（对标自律石头的"自动跳转"）：
 * 打开"触发 App"连续 N 分钟后，自动打开"目标 App"（学习去向）。
 * 计时由 Watcher 完成——前台一切换即清零，这里只负责规则持久化。
 */
class SessionStore(filesDir: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(filesDir, "sessions.json")

    val entries: List<SessionCfg> get() = _entries.value
    private val _entries = kotlinx.coroutines.flow.MutableStateFlow<List<SessionCfg>>(emptyList())

    init {
        runCatching {
            if (file.exists()) {
                _entries.value = json.decodeFromString<SessionData>(file.readText()).items
            }
        }
    }

    @Synchronized
    fun upsert(cfg: SessionCfg) {
        _entries.value = _entries.value.filterNot { it.pkg == cfg.pkg } + cfg
        save()
    }

    @Synchronized
    fun remove(pkg: String) {
        _entries.value = _entries.value.filterNot { it.pkg == pkg }
        save()
    }

    @Synchronized
    fun setEnabled(pkg: String, enabled: Boolean) {
        _entries.value = _entries.value.map {
            if (it.pkg == pkg) it.copy(enabled = enabled) else it
        }
        save()
    }

    private fun save() {
        runCatching {
            file.writeText(json.encodeToString(SessionData(_entries.value.toMutableList())))
        }
    }
}
