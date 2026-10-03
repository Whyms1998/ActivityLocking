package com.activitylock.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * 限额守护：前台服务，屏幕亮着时每分钟统计限额 App 的当日使用时长，
 * 超额即改写拦截清单（跳转目标换成额度提示页）并通知。
 * 屏幕熄灭时暂停统计循环以省电；无限额配置时自动退出。
 */
class QuotaService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var screenOn = true
    private var running = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> { screenOn = true; tick() }
                Intent.ACTION_SCREEN_OFF -> screenOn = false
            }
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (running) tick()
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 前台通知的渠道必须先于 startForeground 创建，否则 Android 14 判定
        // "Bad notification for startForeground" 并杀死进程（闪退循环）
        ensureChannel()
        runCatching {
            startForeground(NOTIFY_ID, buildNotification(null))
        }.onFailure {
            stopSelf()
            return
        }
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        running = true
        handler.post(tickRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tickRunnable)
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tick() {
        runCatching {
            AppStore.ensureInit(this)
            if (!UsageTracker.hasPermission(this)) return
            if (QuotaState.resetIfNewDay(this)) AmsController.writeRules()

            val quota = QuotaState.quotaPackages(AppStore.rules)
            if (quota.isEmpty()) {
                stopSelf()
                return
            }
            val over = QuotaState.overPackages(this)
            var crossedNow = false
            val summary = StringBuilder()
            for ((pkg, min) in quota) {
                val used = UsageTracker.todayMinutes(this, pkg)
                summary.append("${AppStore.findApp(pkg)?.label ?: pkg.substringAfterLast('.')} $used/$min  ")
                if (used >= min && pkg !in over) {
                    QuotaState.markOver(this, pkg)
                    crossedNow = true
                    notifyExceeded(pkg, used, min)
                    // 结束当前会话：正在用的也要立刻停下，否则要等他退出才生效
                    ShizukuManager.run("am", "force-stop", pkg)
                }
            }
            if (crossedNow) AmsController.writeRules()
            // 已超额的包（含升级前标记的）持续补写包级拦截条目
            if (QuotaState.overPackages(this).isNotEmpty()) AmsController.writeRules()
            updateNotification(summary.toString().trim())
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "限额守护", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(summary: String?): Notification {
        ensureChannel()
        val text = summary ?: "监控限额 App 的每日使用时长"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("限额守护")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(summary: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFY_ID, buildNotification(summary))
    }

    private fun notifyExceeded(pkg: String, used: Int, min: Int) {
        val label = AppStore.findApp(pkg)?.label ?: pkg.substringAfterLast('.')
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("「$label」今日额度已用完")
            .setContentText("已使用 $used/$min 分钟，明日 0 点重置")
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFY_EXCEEDED_ID, n)
    }

    companion object {
        private const val CHANNEL_ID = "quota_watch"
        private const val NOTIFY_ID = 2001
        private const val NOTIFY_EXCEEDED_ID = 2002

        /** 有限额配置时启动守护服务；否则停止。任何异常都不能影响调用方。 */
        fun startIfQuotaConfigured(ctx: Context) {
            runCatching {
                AppStore.ensureInit(ctx)
                val hasQuota = AppStore.rules.any { it.quotaMin != null }
                if (hasQuota) {
                    ctx.startForegroundService(Intent(ctx, QuotaService::class.java))
                } else {
                    ctx.stopService(Intent(ctx, QuotaService::class.java))
                }
            }
        }
    }
}
