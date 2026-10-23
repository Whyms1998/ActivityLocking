package com.activitylock.app

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.AmsController
import com.activitylock.app.core.AutoRelock
import com.activitylock.app.core.AutoRelockService
import com.activitylock.app.core.AutoRelockStore
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

        // 自动锁闭：锁屏即锁走本进程广播（低延迟），退出/解锁延迟锁由守护服务轮询
        registerReceiver(AutoRelockScreenOff(), IntentFilter(Intent.ACTION_SCREEN_OFF))

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
                // 自动锁闭：按配置拉起/停止守护服务
                AutoRelock.init(this@LockApp)
                AutoRelockService.sync(this@LockApp)
            }
        }
    }

    private fun ShizukuReady(): Boolean =
        ShizukuManager.installed() && ShizukuManager.granted()
}

/**
 * 自动锁闭·锁屏即锁：进程存活时由本进程直接执行（比服务里等轮询更快）。
 * 进程不在时由 [AutoRelockService] 的常驻广播兜底。
 *
 * ★ v1.14.0：锁屏已从「退出/锁屏」合并开关中独立（[AutoRelock.relockOnScreenOffEnabled]），
 *   且支持逐界面延迟。这里只走**快速路径**——锁「锁屏后延迟 = 0」的那批；
 *   有延迟的交给 [AutoRelockService] 登记到 pending，到点（仍在熄屏状态）再锁回。
 *
 * 注意：这里用 LockEngine 重新上锁，与手动上锁同一条路径——
 * 只影响收藏夹里"当前已解锁"的条目，不会误伤其他应用。
 */
class AutoRelockScreenOff : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SCREEN_OFF) return
        val result = goAsync()
        Thread {
            try {
                AppStore.ensureInit(context)
                AutoRelock.init(context)
                if (AutoRelock.relockOnScreenOffEnabled) {
                    for (rule in AppStore.rules.toList()) {
                        if (rule.locked) continue
                        val pkg = rule.packageName
                        val cls = rule.activityName
                        if (!AutoRelock.isTracked(pkg, cls, AutoRelockStore.LINK_SCREEN_OFF)) continue
                        // 有延迟的一律跳过：由守护服务到点处理
                        if (AutoRelock.effectiveScreenOffDelaySec(pkg, cls) > 0) continue
                        kotlinx.coroutines.runBlocking { AutoRelock.relockOne(pkg, cls) }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                result.finish()
            }
        }.start()
    }
}
