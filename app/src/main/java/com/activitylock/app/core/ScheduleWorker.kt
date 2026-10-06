package com.activitylock.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * 定时计划巡检：每 15 分钟对账一次窗口进出。
 * 精度说明：窗口切换最迟 15 分钟内生效（Doze 下可能更久），
 * 息屏清栈作为日常主力兜底，App 打开时也会立即对账。
 */
class ScheduleWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        AppStore.ensureInit(applicationContext)
        ScheduleEngine.reconcile(applicationContext, killNow = false)
        FocusEngine.reconcile(applicationContext)
        return Result.success()
    }

    companion object {
        fun ensureScheduled(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "schedule_tick",
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<ScheduleWorker>(15, java.util.concurrent.TimeUnit.MINUTES).build()
                )
            }
        }
    }
}

/** 开机后恢复周期巡检并立即对账一次（重启后计划不丢）。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val result = goAsync()
        Thread {
            try {
                AppStore.ensureInit(context)
                ScheduleWorker.ensureScheduled(context)
                ScheduleEngine.reconcile(context, killNow = false)
                FocusEngine.reconcile(context)
            } finally {
                result.finish()
            }
        }.start()
    }
}

/**
 * 息屏广播：清栈（可开关）+ 计划兜底对账。
 * 注意 ACTION_SCREEN_OFF 只能运行时注册，由 LockApp 在进程存活期间注册；
 * 进程被杀时由 WorkManager 巡检兜底。
 */
class ScreenOffReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SCREEN_OFF) return
        val result = goAsync()
        Thread {
            try {
                ScheduleEngine.onScreenOff(context)
            } finally {
                result.finish()
            }
        }.start()
    }
}
