package com.activitylock.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * APP 监督守护（★ v1.32.0 重写）。
 *
 * 与旧版（v1.30~v1.31 的「每日限额」）的根本差别：不再维护"超额集合"，
 * 而是每 3 秒让 [SuperviseEngine] 把所有任务 × 所有对象重新判一遍，
 * 拿到的是一份「此刻该拦谁、因为什么」的快照。读数实时来自 UsageStats，
 * 因此调额度、跨天、时段结束、关任务都会自动生效，不需要任何清理补丁。
 *
 * 执行策略按原因区分：
 *  - 禁止使用 / 时段时长用完 / 当天时长用完 → 现在就该停，整包直接结束进程
 *  - 时段次数用完 / 当天次数用完 → 本次会话属于最后一次，**不打断**；
 *    等确认已离开前台（或下次点击）才拦，避免"设 6 次实际只能用 5 次"
 *  - 界面级对象（如微信›朋友圈）→ 用额度页覆盖当前界面，不杀整包（否则会打断微信聊天）
 */
class QuotaService : Service() {

    private var executor: ScheduledExecutorService? = null

    @Volatile
    private var screenOn = true
    @Volatile
    private var running = false

    /** 同一包 15 秒内只结束一次会话：3 秒一轮，不节流会反复拉 shell 进程。 */
    private val lastKillAt = HashMap<String, Long>()
    /** 额度页拉起节流（同一包 30 秒最多弹一次，避免反复盖屏）。 */
    private val lastGateAt = HashMap<String, Long>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // tick 内含 Shizuku 子进程与 UsageStats 全量遍历，不能跑在主线程
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    executor?.execute { if (running) runCatching { tickFast() } }
                }
                Intent.ACTION_SCREEN_OFF -> screenOn = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        runCatching {
            startForeground(NOTIFY_ID, buildNotification("监督中"))
        }.onFailure {
            stopSelf()
            return
        }
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "supervise").apply { isDaemon = true }
        }
        executor = exec
        runCatching {
            registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
        }
        running = true
        // 快循环 3 秒：判定 + 执行（门禁式，开销只取决于受监督对象数量）
        exec.scheduleWithFixedDelay(
            { if (running) runCatching { tickFast() } }, 1_000L, 3_000L, TimeUnit.MILLISECONDS
        )
        // 慢循环 60 秒：只刷新前台通知摘要，顺带兜住漏跳的快循环
        exec.scheduleWithFixedDelay(
            { if (running) runCatching { tickSlow() } }, 0L, 60_000L, TimeUnit.MILLISECONDS
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        executor?.shutdownNow()
        executor = null
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tickFast() {
        if (!screenOn) return
        runCatching {
            AppStore.ensureInit(this)
            val active = AppStore.tasks.any {
                it.enabled && it.rules.active && it.targets.isNotEmpty()
            }
            if (!active) {
                SuperviseEngine.clear(this)
                stopSelf()
                return
            }
            if (!UsageTracker.hasPermission(this)) {
                updateNotification("缺少「使用情况访问权限」，监督无法生效")
                return
            }
            val changed = SuperviseEngine.tick(this)
            if (changed) AmsController.writeRules()
            enforce()
        }
    }

    /** 按原因执行拦截动作。 */
    private fun enforce() {
        val blocks = SuperviseEngine.blocks(this)
        if (blocks.isEmpty()) return
        val now = System.currentTimeMillis()
        for (b in blocks) {
            // ★ v1.33.2：**不在前台就什么都不做**。旧版只要快照里有超额对象就
            //   每 30 秒 force-stop + 弹额度页一次（服务重建还会立即再弹）——
            //   用户在桌面没开这个 App 也会被额度页反复盖脸（真机实锤）。
            //   被拦对象的入口由 watcher 组件规则守着：点图标 → 拦下 → 弹额度页。
            if (!UsageTracker.isInForeground(this, b.pkg)) continue
            when (b.reason) {
                // 现在就该停：整包结束进程（界面级只盖额度页，见下）
                "forbid", "window_sec", "day_sec" -> {
                    if (b.isWholeApp) {
                        killIfDue(b.pkg, now, showGate = true)
                    } else {
                        showGateIfDue(b.pkg, now)
                    }
                }
                // 次数用完：本次会话属于最后一次，**不打断**（否则设 6 次实际只能用 5 次）；
                // 但一旦确认已离开前台就结束进程，堵住「从最近任务切回还能继续用」的绕过。
                // 界面级对象离开后组件拦截已生效，无需额外动作。
                "window_cnt", "day_cnt" -> {
                    if (b.isWholeApp) {
                        killIfDue(b.pkg, now, showGate = false)
                    }
                }
            }
        }
    }

    private fun killIfDue(pkg: String, now: Long, showGate: Boolean) {
        val last = lastKillAt[pkg] ?: 0L
        if (now - last < 15_000L) return
        lastKillAt[pkg] = now
        ShizukuManager.run("am", "force-stop", pkg)
        Log.i(TAG, "force-stop $pkg")
        if (showGate) showGateIfDue(pkg, now)
    }

    /** 用额度说明页覆盖当前界面（shell 身份，绕过后台 Activity 启动限制）。 */
    private fun showGateIfDue(pkg: String, now: Long) {
        val last = lastGateAt[pkg] ?: 0L
        if (now - last < 30_000L) return
        lastGateAt[pkg] = now
        // ★ 带上被拦的包名：额度页据此只讲这一个对象，不罗列全部超额项
        ShizukuManager.run(
            "am", "start", "-n", AmsController.QUOTA_GATE_COMPONENT, "--es", "pkg", pkg
        )
    }

    private fun tickSlow() {
        // 顺带当看门狗：watcher 主循环停摆时（进程还在、规则不再 reload）
        // 自动杀掉重拉。检查很轻（读一个十几字节的文件），60 秒一次足够。
        runCatching { AmsController.checkHeartbeat() }
        val blocks = SuperviseEngine.blocks(this)
        val text = if (blocks.isEmpty()) {
            "监督中，${AppStore.tasks.count { it.enabled && it.rules.active }} 个任务生效"
        } else {
            blocks.joinToString("，") { "${it.key.substringAfterLast('/')} ${reasonShort(it.reason)}" }
        }
        updateNotification(text)
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "APP 监督", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("APP 监督")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "Supervise"
        private const val CHANNEL_ID = "supervise_watch"
        private const val NOTIFY_ID = 2001

        fun reasonShort(reason: String): String = when (reason) {
            "forbid" -> "禁止使用"
            "window_sec" -> "本时段时长用完"
            "window_cnt" -> "本时段次数用完"
            "day_sec" -> "今日时长用完"
            "day_cnt" -> "今日次数用完"
            else -> "已达额度"
        }

        /** 有生效中的监督任务就启动守护，否则停止。任何异常都不影响调用方。 */
        fun startIfQuotaConfigured(ctx: Context) {
            runCatching {
                AppStore.ensureInit(ctx)
                val has = AppStore.tasks.any { it.enabled && it.rules.active && it.targets.isNotEmpty() }
                if (has) ctx.startForegroundService(Intent(ctx, QuotaService::class.java))
                else ctx.stopService(Intent(ctx, QuotaService::class.java))
            }
        }
    }
}
