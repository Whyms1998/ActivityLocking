package com.activitylock.app

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.AmsController
import com.activitylock.app.core.FocusEngine
import com.activitylock.app.core.FocusState
import com.activitylock.app.core.QuotaService
import com.activitylock.app.core.ScheduleEngine
import com.activitylock.app.core.ScheduleWorker
import com.activitylock.app.core.ScreenOffReceiver
import com.activitylock.app.core.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStore.init(this)

        // 息屏清栈 + 定时计划兜底：进程存活期间监听息屏广播
        registerReceiver(ScreenOffReceiver(), IntentFilter(Intent.ACTION_SCREEN_OFF))

        // 启动维护协程。整段防御：任何异常都不能让 App 打不开。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                // 限额守护不依赖 Shizuku，先行启动
                QuotaService.startIfQuotaConfigured(this@LockApp)

                // 冷启动时 Shizuku 服务连接存在竞态，最多等待约 16 秒
                var tries = 0
                while (tries < 8 && !ShizukuReady()) {
                    delay(2000)
                    tries++
                }

                if (AppStore.rules.any { it.block }) {
                    AmsController.writeRules()
                    if (ShizukuReady()) AmsController.ensureStarted()
                }
                // 定时计划：开机/进程重启后恢复周期巡检，并立即对账一次
                if (AppStore.schedules.isNotEmpty()) {
                    ScheduleWorker.ensureScheduled(this@LockApp)
                    ScheduleEngine.reconcile(this@LockApp, killNow = false)
                }
                // 专注会话：到点收尾/恢复倒计时服务
                FocusEngine.reconcile(this@LockApp)
                if (FocusState.load(this@LockApp) != null) {
                    com.activitylock.app.core.FocusService.start(this@LockApp)
                }
            }
        }
    }

    private fun ShizukuReady(): Boolean =
        ShizukuManager.installed() && ShizukuManager.granted()
}
