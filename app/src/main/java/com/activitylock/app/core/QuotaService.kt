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
 * 限额守护：前台服务，屏幕亮着时每分钟统计限额 App 的当日使用时长，
 * 超额即改写拦截清单（跳转目标换成额度提示页）并通知。
 * 屏幕熄灭时暂停统计循环以省电；无限额配置时自动退出。
 */
class QuotaService : Service() {

    private var executor: ScheduledExecutorService? = null

    /** 跨线程读写（广播在主线程写、轮询线程读）→ volatile。 */
    @Volatile
    private var screenOn = true
    @Volatile
    private var running = false

    // ★ v1.31.2：次数维度走「3 秒增量快检」——缓存当日累计次数，按增量窗口累加。
    //   只在 quota 线程访问，无需加锁；跨天 / 服务重启时用全天统计重新播种。
    private val cntCache = HashMap<String, Int>()
    private var cntDay: String = ""
    private var cntSeenUntil: Long = 0L

    /** 超限包的上次 force-stop 时间：快循环 3 秒一轮，必须节流，否则每隔几秒拉一次 shell。 */
    private val lastKillAt = HashMap<String, Long>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // ★ tick() 内含 Shizuku 子进程（am force-stop）、UsageStats 全量遍历、
                //   文件 IO（writeRules），onReceive 默认跑在主线程 → 必然 ANR。
                //   统一派发到后台轮询线程执行。
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    executor?.execute { if (running) runCatching { tick() } }
                }
                Intent.ACTION_SCREEN_OFF -> screenOn = false
            }
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
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "quota").apply { isDaemon = true }
        }
        executor = exec
        runCatching {
            registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
        }
        running = true
        // scheduleWithFixedDelay：上次跑完才排下次，不会堆积任务
        // 慢循环（60s）：时长维度全量统计（UsageStats 全天遍历较重）
        exec.scheduleWithFixedDelay(
            { if (running) runCatching { tick() } }, 0L, 60_000L, TimeUnit.MILLISECONDS
        )
        // ★ v1.31.2 快循环（3s）：次数维度增量检查——次数是「打开即计」的事件，
        //   挂 60s 巡检会让「连开七八次」全部落在未判定的窗口里（真机复现：
        //   设 6 次、实际开到 7 次都没触发拦截），必须秒级判定才谈得上生效。
        exec.scheduleWithFixedDelay(
            { if (running) runCatching { tickCount() } }, 1_000L, 3_000L, TimeUnit.MILLISECONDS
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        running = false
        executor?.shutdownNow()
        executor = null
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tick() {
        // 息屏时暂停统计循环（省电）。原实现只在接收器里记 screenOn 却从不读，
        // 类注释承诺的"屏幕熄灭时暂停"实际未生效，息屏后仍每 60s 全量查询。
        if (!screenOn) return
        runCatching {
            AppStore.ensureInit(this)
            if (!UsageTracker.hasPermission(this)) return
            if (QuotaState.resetIfNewDay(this)) AmsController.writeRules()
            // ★ v1.30.2：自愈——限额被调大/取消后残留的超额状态在这里兜底解除
            reevaluateOver(this)

            val quota = QuotaState.quotaPackages(AppStore.rules)
            val cntQuota = QuotaState.quotaCntPackages(AppStore.rules)
            // ★ v1.30.0：时长与次数任一维度有配置就继续守护（次数是事件驱动，无需轮询）
            if (quota.isEmpty() && cntQuota.isEmpty()) {
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
            // ★ v1.31.2：次数维度改由 3 秒快循环（tickCount）判定，这里不再重复处理，
            //   避免两处各自 force-stop / 发通知。
            if (crossedNow) AmsController.writeRules()
            // 诊断：每分钟把次数缓存读数打一次（排查「限额没生效」时看这里，
            // 缓存值应与限额页「今日已打开 X/Y」一致）
            if (cntCache.isNotEmpty()) {
                Log.i(TAG, "cntCache=" + cntCache.entries.joinToString { (k, v) ->
                    "${k.substringAfterLast('.')}:$v/${cntQuota[k] ?: "-"}"
                })
            }
            // 已超额的包（含升级前标记的）持续补写包级拦截条目
            if (QuotaState.overPackages(this).isNotEmpty()) AmsController.writeRules()
            updateNotification(summary.toString().trim())
        }
    }

    /**
     * ★ v1.31.2：次数维度快检（3 秒一次，增量事件窗口）。
     *
     * 语义（与限额页文案一致）：
     *  - 第 N 次打开**本身可用**（打开即计 1 次，达到上限只标超额、不打断当前会话）；
     *  - 第 N+1 次起：判定超额 → 包级拦截写进 blocked.json（下次新启动直接被拦到额度页），
     *    并对仍在跑的这个包 `am force-stop`——因为「回前台 / 点图标恢复任务」不会产生
     *    新的 activity 启动，系统不给我们拦截回调，只有杀掉进程才能让下一次是全新启动。
     */
    private fun tickCount() {
        if (!screenOn) return
        runCatching {
            AppStore.ensureInit(this)
            val cntQuota = QuotaState.quotaCntPackages(AppStore.rules)
            if (cntQuota.isEmpty()) {
                cntCache.clear(); cntDay = ""; cntSeenUntil = 0L
                return@runCatching
            }
            if (!UsageTracker.hasPermission(this)) return@runCatching
            if (QuotaState.resetIfNewDay(this)) {
                AmsController.writeRules()
                cntCache.clear(); cntDay = ""; cntSeenUntil = 0L
            }
            val day = todayStr()
            if (day != cntDay) {
                cntCache.clear(); cntSeenUntil = 0L; cntDay = day
            }
            for (pkg in cntQuota.keys) {
                val entry = entryClsOf(pkg)
                val known = cntCache[pkg]
                when {
                    // 没有入口界面锚点 → 增量无法判会话边界，直接全天重算（包数少，可接受）
                    entry == null -> cntCache[pkg] = UsageTracker.todayOpenCount(this, pkg, null)
                    // 首次播种 / 新配置的包：全天统计一次作为基数
                    known == null || cntSeenUntil == 0L ->
                        cntCache[pkg] = UsageTracker.todayOpenCount(this, pkg, entry)
                    else -> {
                        val add = UsageTracker.countSince(this, pkg, entry, cntSeenUntil)
                        if (add > 0) {
                            cntCache[pkg] = known + add
                            Log.i(TAG, "count $pkg +$add -> ${known + add}")
                        }
                    }
                }
            }
            cntSeenUntil = System.currentTimeMillis()
            var dirty = false
            val over = QuotaState.overPackages(this)
            for ((pkg, limit) in cntQuota) {
                val opens = cntCache[pkg] ?: continue
                if (pkg !in over && opens >= limit) {
                    QuotaState.markOver(this, pkg)
                    notifyCntExceeded(pkg, opens, limit)
                    dirty = true
                    Log.i(TAG, "count over: $pkg $opens/$limit")
                }
                // 超过上限（第 N+1 次起）→ 结束当前会话，堵住「回前台不算新启动」的绕过。
                // 15 秒节流：本循环 3 秒一轮，不节流会每隔几秒拉起一次 shell 进程。
                if (opens > limit) {
                    val last = lastKillAt[pkg] ?: 0L
                    val now = System.currentTimeMillis()
                    if (now - last >= 15_000L) {
                        lastKillAt[pkg] = now
                        ShizukuManager.run("am", "force-stop", pkg)
                    }
                }
            }
            if (dirty) AmsController.writeRules()
        }
    }

    private fun todayStr(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

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

    /** ★ v1.31.0：次数维度超额通知。 */
    private fun notifyCntExceeded(pkg: String, used: Int, limit: Int) {
        val label = AppStore.findApp(pkg)?.label ?: pkg.substringAfterLast('.')
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("「$label」今日打开次数已用完")
            .setContentText("已打开 $used/$limit 次，明日 0 点重置")
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFY_EXCEEDED_ID, n)
    }

    companion object {
        private const val TAG = "QuotaSvc"
        private const val CHANNEL_ID = "quota_watch"

        /** 该包的桌面入口类名（「打开次数」的计数锚点；扫不到为 null 时退化为前台会话数）。 */
        private fun entryClsOf(pkg: String): String? =
            AppStore.findApp(pkg)?.activities?.firstOrNull { it.isLauncher }?.cls

        private const val NOTIFY_ID = 2001
        private const val NOTIFY_EXCEEDED_ID = 2002

        /** 有限额配置（时长或次数）时启动守护服务；否则停止。任何异常都不能影响调用方。 */
        fun startIfQuotaConfigured(ctx: Context) {
            runCatching {
                AppStore.ensureInit(ctx)
                val hasQuota = AppStore.rules.any { it.quotaMin != null || it.quotaCnt != null }
                if (hasQuota) {
                    ctx.startForegroundService(Intent(ctx, QuotaService::class.java))
                } else {
                    ctx.stopService(Intent(ctx, QuotaService::class.java))
                }
            }
        }

        /**
         * ★ v1.30.2：重估超额集合。`over` 只在「触线瞬间」写入、次日惰性清空，
         *   此前**调大限额 / 取消限额都不会解除**——测试时随手解锁几次把次数耗光后，
         *   就算把限额改到 999 或留空，整包拦截仍然生效到当天结束（用户感知：
         *   「明明没有超额但是也不让进去应用」）。这里对每个超额包重新判两个维度：
         *   限额全撤 → 立即解除；两维度实测都不超 → 立即解除。任何配置变更
         *   （编辑界面保存 / 配额页新增移除）后都应调用一次；服务巡检开头也调用，
         *   兜住漏网路径。只在「解除」方向工作，不主动新增超额。
         */
        fun reevaluateOver(ctx: Context) {
            runCatching {
                AppStore.ensureInit(ctx)
                val over = QuotaState.overPackages(ctx)
                if (over.isEmpty()) return
                val timeQ = QuotaState.quotaPackages(AppStore.rules)
                val cntQ = QuotaState.quotaCntPackages(AppStore.rules)
                var changed = false
                for (pkg in over.toList()) {
                    val tLimit = timeQ[pkg]
                    val cLimit = cntQ[pkg]
                    if (tLimit == null && cLimit == null) {
                        // 限额已全部取消：超额状态必须跟着走，否则「删了限额还被拦」
                        QuotaState.clearOver(ctx, pkg)
                        changed = true
                        continue
                    }
                    val cOver = cLimit != null && runCatching {
                        UsageTracker.todayOpenCount(ctx, pkg, entryClsOf(pkg)) >= cLimit
                    }.getOrDefault(true)
                    // 无使用时长权限时无法确认 → 保守视为仍超额（不解除），交给有权限时的下一轮
                    val tOver = tLimit != null && runCatching {
                        if (!UsageTracker.hasPermission(ctx)) true
                        else UsageTracker.todayMinutes(ctx, pkg) >= tLimit
                    }.getOrDefault(true)
                    if (!cOver && !tOver) {
                        QuotaState.clearOver(ctx, pkg)
                        changed = true
                    }
                }
                if (changed) AmsController.writeRules()
            }
        }
    }
}
