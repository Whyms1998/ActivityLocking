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

    var history by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set

    private lateinit var ruleStore: RuleStore
    private lateinit var historyStore: HistoryStore
    private lateinit var appContext: Context
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    fun init(context: Context) {
        if (::ruleStore.isInitialized) return
        appContext = context.applicationContext
        ruleStore = RuleStore(appContext.filesDir)
        historyStore = HistoryStore(appContext.filesDir)
        rules = ruleStore.rules
        history = historyStore.entries
        reloadApps()
    }

    fun ensureInit(context: Context) = init(context)

    fun context(): Context = appContext

    fun reloadApps() {
        appsLoading = true
        scope.launch {
            val list = withContext(Dispatchers.IO) { PmRepository.loadApps(appContext) }
            apps = list
            appsLoading = false
        }
    }

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

    fun addRule(pkg: String, cls: String, note: String = "", block: Boolean = false) {
        if (ruleStore.contains(pkg, cls)) {
            ruleStore.updateLocked(pkg, cls, true)
        } else {
            ruleStore.add(LockRule(pkg, cls, note, block))
        }
        rules = ruleStore.rules
    }

    fun updateLocked(pkg: String, cls: String, locked: Boolean) {
        ruleStore.updateLocked(pkg, cls, locked)
        rules = ruleStore.rules
    }

    fun hasRule(pkg: String, cls: String): Boolean =
        ::ruleStore.isInitialized && ruleStore.contains(pkg, cls)

    fun recordHistory(pkg: String, cls: String, mode: String, action: String) {
        if (!::historyStore.isInitialized) return
        val note = rules.firstOrNull { it.packageName == pkg && it.activityName == cls }?.note ?: ""
        historyStore.add(HistoryEntry(pkg, cls, note, mode, action))
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
        ruleStore.remove(pkg, cls)
        rules = ruleStore.rules
    }

    fun updateNote(pkg: String, cls: String, note: String) {
        ruleStore.updateNote(pkg, cls, note)
        rules = ruleStore.rules
    }
}
