package com.activitylock.app

import android.app.Application
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.AmsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStore.init(this)
        // App 启动时同步拦截清单并确保常驻 Watcher 存活（被系统重启等情况后自动恢复）
        CoroutineScope(Dispatchers.IO).launch {
            if (AppStore.rules.any { it.block }) {
                AmsController.writeRules()
                if (ShizukuReady()) AmsController.ensureStarted()
            }
        }
    }

    private fun ShizukuReady(): Boolean =
        com.activitylock.app.core.ShizukuManager.installed() &&
            com.activitylock.app.core.ShizukuManager.granted()
}
