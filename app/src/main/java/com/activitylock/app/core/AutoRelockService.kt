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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 自动锁闭守护服务（前台服务）。
 *
 * 与 [AutoFreezeService] 同构，但执行的是 [LockEngine] 重新上锁（图标保留、点击被拦），
 * 而非整包冻结。
 *
 * 三条触发链路（与 [AutoRelockStore.LINK_*] 一一对应）：
 *  1. **解锁后重新锁**：界面通过验证解锁（[AutoRelock.noteUnlocked] 埋点）后开始计时，
 *     到各自延迟时重新上锁。★ v1.30.0：期间目标在前台则**暂停计时**（保留累计进度，
 *     旧实现是直接放弃待锁），离开前台后从暂停处续算 —— 「解锁后一直停在 App 里用」
 *     不再导致这条链路永远不锁回。
 *  2. **退出后重新锁**：目标应用离开前台 → 按各自延迟计时后重新上锁；
 *     届时把该包下**全部**已解锁、且该界面仍参与「退出后」链路的条目一并锁回
 *     （v1.24.10 起；此前只锁第一条，多界面应用会漏）。
 *  3. **锁屏后重新锁**：ACTION_SCREEN_OFF → 延迟 0 立即锁；延迟 > 0 记入
 *     [screenOffPending]，到点且**仍处熄屏**才锁回。
 *
 * 性能设计（沿用自动冻结的结论）：
 *  - 前台查询与上锁动作**全部在后台单线程**；
 *  - 延迟用 pending 表（key → 解锁时刻）而非 postDelayed，不堆积任务；
 *  - 名单为空 / 两个开关都关 → 不查询（零开销）。
 */
class AutoRelockService : Service() {

    private var executor: ScheduledExecutorService? = null

    /** ★ 跨线程读写：主线程（广播）写、executor 线程读，必须 volatile。 */
    @Volatile
    private var screenOn = true

    /**
     * 退出延迟链路：pkg → 待锁记录。
     * ★ v1.29.0（S9）：原来只是 `Long`（离开时刻），服务一被回收就全空；
     *   现在升级为 [PendingRec] 并落盘，重建后能恢复。
     * **只在 executor 线程访问**，避免与 tick 并发迭代抛 ConcurrentModificationException。
     */
    private val exitPending = LinkedHashMap<String, PendingRec>()

    /**
     * 锁屏延迟链路：key = "pkg/cls" → 待锁记录。
     *
     * ★ v1.29.0（S10）：语义从「连续熄屏 N 分钟」改为**累计熄屏 N 分钟**。
     *   熄屏时 `since = 熄屏时刻`；亮屏时把这段计进 `accMs` 并把 `since` 置 0（暂停，不清零）。
     *   原实现亮屏就整批清空，用户中途看一眼时间，30 分钟档永远到不了。
     * **只在 executor 线程访问**。
     */
    private val screenOffPending = LinkedHashMap<String, PendingRec>()

    /** ★ v1.29.0（S9）：待锁表持久化。 */
    private var pendingStore: RelockPendingStore? = null

    private var lastForeground: String = ""

    /**
     * 去重节流（文档 §13.3）：同一「应用/界面」在 [THROTTLE_MS] 内只允许真正执行一次
     * 上锁动作。
     *
     * ★ 为什么必须有：快速亮灭屏时，`ACTION_SCREEN_OFF` 会在 1~2 秒内连发；
     *   「锁屏后」链路延迟为 0（默认）时，每次都会走一遍 `LockEngine.apply` ——
     *   即 N 次 Shizuku 子进程 + N 次写规则，纯属浪费，还可能把刚解锁的界面
     *   再锁一次。有了节流，3 秒内的重复触发直接丢弃。
     *
     * **只在 executor 线程访问**（与其它 pending 表同线程，无需加锁）。
     */
    private val lastLockAt = HashMap<String, Long>()

    /** 返回 true 表示允许执行，并记录本次时刻。key 形如 "pkg/cls"。 */
    private fun mayLock(key: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - (lastLockAt[key] ?: 0L) < THROTTLE_MS) return false
        lastLockAt[key] = now
        return true
    }

    /**
     * ★ v1.24.11：重锁失败必须可见。
     *   此前 LockEngine 失败（Shizuku 掉线、拦截进程起不来）时 `relockOne` 返回 false，
     *   计数不加、通知不更新、异常被 runCatching 吞掉 —— 用户看到的就是「功能坏了，
     *   什么都没发生」，排查无从下手（2026-10-14 真机排查的教训之一）。
     *   通知是常驻前台通知，更新文本即可；setOnlyAlertOnce 保证不响铃不打扰。
     */
    private fun noteFailure(site: String, key: String) {
        android.util.Log.w("AutoRelock", "$site 重锁失败：$key")
        updateNotification("有界面重锁失败，请检查 Shizuku 是否存活")
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    // ★ onScreenOff() 内部是 runBlocking + Shizuku 子进程 + 重新上锁，
                    //   直接在 onReceive（主线程）调用会阻塞主线程 → ANR。
                    //   派发到 executor；各 pending 表的清理一并移入，保证单线程访问。
                    executor?.execute {
                        exitPending.clear()
                        onScreenOff()
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    // ★ v1.29.0（S10）：亮屏只**暂停**熄屏计时，不清零。
                    //   原实现是整批 clear()（注释写「中途亮屏回来则整批放弃」），
                    //   于是「熄屏后 30 分钟」只要中途看一眼时间，计时就永久归零重来，
                    //   30 分钟档在真实使用中几乎不可能到达。
                    executor?.execute { pauseScreenOffClock() }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        runCatching { startForeground(NOTIFY_ID, buildNotification("准备中")) }.onFailure {
            stopSelf()
            return
        }
        // ★ v1.29.0（S9）：**必须在 executor 起来之前恢复三张待锁表**，
        //   否则 tick 第一拍就会读到空表，把整批待锁项当成"不存在"。
        pendingStore = RelockPendingStore(filesDir)
        screenOn = runCatching {
            getSystemService(android.os.PowerManager::class.java).isInteractive
        }.getOrDefault(true)
        restorePending()
        // ★ 先建好 executor 再注册广播：onReceive 会把工作 schedule 到 executor，
        //   若注册在前，窗口期内到达的锁屏广播会因 executor 为 null 而静默丢弃。
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "autorelock").apply { isDaemon = true }
        }
        executor = exec
        runCatching {
            registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            })
        }
        exec.scheduleWithFixedDelay({ safeTick() }, 0L, TICK_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * 从磁盘恢复退出 / 锁屏两张表（解锁后那张由 [AutoRelock] 自己恢复）。
     *
     * 判据与 [PendingRec] 注释一致：
     *  - 同一次开机 → 续算；
     *  - 跨重启 → elapsed 已重置，判为立即到期；
     *  - 锁屏链路若记录时正在计时、而此刻屏幕是亮的 → 结算那段后转为暂停。
     */
    private fun restorePending() {
        val snap = pendingStore?.load() ?: return
        if (snap.exit.isEmpty() && snap.screenOff.isEmpty()) return
        val now = SystemClock.elapsedRealtime()

        for (r in snap.exit) {
            exitPending[r.key] = if (BootClock.sameBoot(r.boot)) r
            else r.copy(accMs = Long.MAX_VALUE / 4, since = 0L)
        }

        for (r in snap.screenOff) {
            screenOffPending[r.key] = when {
                !BootClock.sameBoot(r.boot) ->
                    r.copy(accMs = Long.MAX_VALUE / 4, since = 0L)
                // 正在计时段内，但重建这一刻屏幕亮着 → 把这段计入累计，暂停等下次熄屏
                r.since > 0L && screenOn ->
                    r.copy(accMs = r.accMs + (now - r.since).coerceAtLeast(0L), since = 0L)
                else -> r
            }
        }
        persistPending()
    }

    /** 把退出 / 锁屏两张表写回磁盘（不碰解锁后那张，它由 [AutoRelock] 负责）。 */
    private fun persistPending() {
        pendingStore?.update {
            it.copy(
                exit = exitPending.values.toList(),
                screenOff = screenOffPending.values.toList(),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        executor?.shutdownNow()
        executor = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 只在确实需要时才做重活，任何异常都不能让服务挂掉。 */
    private fun safeTick() {
        runCatching { tick() }
    }

    /**
     * 亮屏时把锁屏链路的计时段结算进 `accMs` 并暂停（S10）。
     *
     * 遍历前先取 key 快照 —— 边遍历边改 LinkedHashMap 会抛
     * ConcurrentModificationException（这条在 tick 线程里抛会静默杀掉整个调度）。
     */
    private fun pauseScreenOffClock() {
        if (screenOffPending.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        var touched = false
        for (k in screenOffPending.keys.toList()) {
            val v = screenOffPending[k] ?: continue
            if (v.since > 0L) {
                screenOffPending[k] = v.copy(
                    accMs = v.accMs + (now - v.since).coerceAtLeast(0L),
                    since = 0L,
                    wall = System.currentTimeMillis(),
                    boot = BootClock.mark(),
                )
                touched = true
            }
        }
        if (touched) persistPending()
    }

    private fun tick() {
        AppStore.ensureInit(this)
        AutoRelock.init(this)
        // 三个开关都关 或 名单为空 → 不查询（省电）
        if (!AutoRelock.active()) {
            if (exitPending.isNotEmpty() || screenOffPending.isNotEmpty()) {
                exitPending.clear()
                screenOffPending.clear()
                persistPending()
            }
            return
        }

        // ---- 链路 ①：解锁后重新锁（纯计时；熄屏期间也要继续走，
        //      否则挂起的待锁项会一直悬着，直到下次亮屏才可能落地）----
        if (AutoRelock.relockAfterUnlockEnabled) tickAfterUnlock()

        // ---- 链路 ③：锁屏延迟重新锁（只在熄屏时需要跑）----
        if (AutoRelock.relockOnScreenOffEnabled && !screenOn) tickScreenOffDelay()

        // ---- 链路 ②：退出延迟重新锁（需要前台查询，只在亮屏时有意义）----
        if (!AutoRelock.relockOnExitEnabled || !screenOn) return
        tickExitDelay()
    }

    /** 处理「解锁后重新锁」：扫描 pendingRelock，到点重新上锁。 */
    private fun tickAfterUnlock() {
        val pend = AutoRelock.pendingRelock
        if (pend.isEmpty()) return
        val now = SystemClock.elapsedRealtime()

        // ★ v1.30.0：前台豁免从「放弃待锁」改为「暂停 / 恢复」。
        //   只要有解锁待锁项，每一拍都要知道此刻前台（暂停判定 + 恢复判定都依赖它），
        //   常态（无待锁项）零开销。`lastForeground` 仅在「退出后」链路开启时才被
        //   tickExitDelay 维护，只作冗余兜底，以前台查询结果为准。
        //   历史包袱说明：v1.24.10 之前只依赖 lastForeground，只开「解锁后」的用户
        //   该值恒为空串，正在用的 App 会被到点锁掉 —— 所以查询必须独立拿。
        val fgNow = currentForeground()

        var changed = false
        for ((key, rec) in pend) {
            val (pkg, cls) = AutoRelock.parseKey(key)
            // 该应用的「解锁后」链路已被关掉 → 放弃这次待锁（否则关掉开关还会锁回）
            if (!AutoRelock.isTracked(pkg, cls, AutoRelockStore.LINK_UNLOCK)) {
                AutoRelock.clearPendingKey(key)
                continue
            }
            // ★ v1.30.0：正在用 → 暂停计时（保留累计进度），离开前台后续算到点锁回。
            //   旧实现（v1.24.10~v1.29.1）在这里 clearPendingKey 直接放弃 —— 用户解锁后
            //   一直停在这个 App 里撑过延迟，这条链路就永远不会再锁回，感知为「失效」。
            if (pkg == fgNow || pkg == lastForeground) {
                if (rec.since > 0L) AutoRelock.pausePending(key)
                continue
            }
            // 暂停中且已不在前台 → 恢复计时，下一拍继续按延迟判到期
            if (rec.since == 0L) {
                AutoRelock.resumePending(key)
                continue
            }
            if (!rec.isDue(now, AutoRelock.effectiveDelaySec(pkg, cls))) continue
            // 节流：3 秒内已经锁过 → 视为已完成，清掉待锁项
            if (!mayLock(key)) {
                AutoRelock.clearPendingKey(key)
                continue
            }
            val ok = runBlocking { AutoRelock.relockOne(pkg, cls) }
            if (ok) changed = true else noteFailure("解锁后", key)
        }
        if (changed) updateNotification(noticeText())
    }

    /** 新建一条「从此刻起持续计时」的待锁记录。 */
    private fun newRec(key: String, lane: Int, since: Long): PendingRec = PendingRec(
        key = key,
        lane = lane,
        accMs = 0L,
        since = since,
        wall = System.currentTimeMillis(),
        boot = BootClock.mark(),
    )

    /** 处理「退出延迟重新锁」。 */
    private fun tickExitDelay() {
        val fg = currentForeground()
        if (fg.isNotEmpty() && fg != lastForeground) {
            val prev = lastForeground
            if (prev.isNotEmpty() && AutoRelock.isTracked(prev, null, AutoRelockStore.LINK_EXIT)) {
                exitPending[prev] = newRec(prev, AutoRelockStore.LINK_EXIT, SystemClock.elapsedRealtime())
                persistPending()
            }
            if (AutoRelock.isTracked(fg, null, AutoRelockStore.LINK_EXIT) && exitPending.remove(fg) != null) {
                persistPending()
            }
            lastForeground = fg
        }

        if (exitPending.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val due = exitPending.filter { (pkg, rec) ->
            // ★ 退出链路专用延迟（与「解锁后」延迟相互独立）
            rec.isDue(now, AutoRelock.effectiveExitDelaySec(pkg, null))
        }.keys.toList()
        if (due.isEmpty()) return

        var changed = false
        for (pkg in due) {
            exitPending.remove(pkg)
            if (pkg == lastForeground) continue
            // ★ v1.24.10 补齐到点二次校验（与另两条链路同口径）：
            //   等待期间用户把该应用的「退出后」链路关掉、或把整个应用移出管辖
            //   → 放弃这次重锁。原先只有另两条链路有这道校验，退出链路没有 ——
            //   于是"关了开关还会被锁回去"。
            if (!AutoRelock.isTracked(pkg, null, AutoRelockStore.LINK_EXIT)) continue
            if (!mayLock("$pkg/*")) continue
            // ★ pass LINK_EXIT：让 relockOne 按「退出后」链路逐条过滤该包的多个界面
            //   （原实现只锁第一条，见 AutoRelock.resolveTargets 的说明）。
            val ok = runBlocking { AutoRelock.relockOne(pkg, null, AutoRelockStore.LINK_EXIT) }
            if (ok) changed = true else noteFailure("退出后", "$pkg/*")
        }
        // 无论这次是否真的锁成功，待锁项都已经从表里移除了 → 必须落盘（S9）
        persistPending()
        if (changed) updateNotification(noticeText())
    }

    /**
     * 锁屏 → 把受管辖的已解锁界面重新锁上。
     *
     * ★ v1.14.0：锁屏从「退出/锁屏」合并开关中独立出来，并支持**逐界面延迟**：
     *   - 延迟 = 0 → 立即锁；
     *   - 延迟 > 0 → 记入 [screenOffPending]，由 [tickScreenOffDelay] 到点锁回；
     *     期间用户若亮屏回来（ACTION_SCREEN_ON）则整批放弃。
     */
    private fun onScreenOff() {
        runCatching {
            AppStore.ensureInit(this)
            AutoRelock.init(this)
            if (!AutoRelock.relockOnScreenOffEnabled) {
                if (screenOffPending.isNotEmpty()) {
                    screenOffPending.clear()
                    persistPending()
                }
                return
            }
            val now = SystemClock.elapsedRealtime()
            var n = 0
            var touched = false
            for (rule in AppStore.rules.toList()) {
                if (rule.locked) continue
                val pkg = rule.packageName
                val cls = rule.activityName
                val key = "$pkg/$cls"
                if (!AutoRelock.isTracked(pkg, cls, AutoRelockStore.LINK_SCREEN_OFF)) continue
                val delay = AutoRelock.effectiveScreenOffDelaySec(pkg, cls)
                if (delay <= 0) {
                    // 立即锁：顺手清掉可能存在的旧累计记录，免得下次熄屏带着旧进度秒锁
                    if (screenOffPending.remove(key) != null) touched = true
                    if (!mayLock(key)) continue
                    if (runBlocking { AutoRelock.relockOne(pkg, cls) }) n++
                    else noteFailure("锁屏", key)
                } else {
                    // ★ v1.29.0（S10）：**不再 clear() 整批**，有累计进度就续上。
                    //   旧实现进来先 clear 再重建，等于每次熄屏都从零开始算 ——
                    //   配合旧版"亮屏即清空"，30 分钟档几乎不可能到达。
                    val old = screenOffPending[key]
                    screenOffPending[key] = if (old == null) {
                        newRec(key, AutoRelockStore.LINK_SCREEN_OFF, now)
                    } else {
                        old.copy(
                            since = now,
                            wall = System.currentTimeMillis(),
                            boot = BootClock.mark(),
                        )
                    }
                    touched = true
                }
            }
            if (touched) persistPending()
            if (n > 0) updateNotification("锁屏已重新锁 $n 个界面")
        }
    }

    /** 锁屏延迟到点 → 把仍在熄屏状态下待锁的界面锁回。 */
    private fun tickScreenOffDelay() {
        if (screenOffPending.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val due = screenOffPending.filter { (key, rec) ->
            val (pkg, cls) = AutoRelock.parseKey(key)
            rec.isDue(now, AutoRelock.effectiveScreenOffDelaySec(pkg, cls))
        }.keys.toList()
        if (due.isEmpty()) return
        var n = 0
        for (key in due) {
            screenOffPending.remove(key)
            val (pkg, cls) = AutoRelock.parseKey(key)
            // 等待期间用户把该应用的「锁屏后」链路关掉了 → 不再锁回
            if (!AutoRelock.isTracked(pkg, cls, AutoRelockStore.LINK_SCREEN_OFF)) continue
            if (!mayLock("$pkg/$cls")) continue
            if (runBlocking { AutoRelock.relockOne(pkg, cls) }) n++
            else noteFailure("锁屏延迟", "$pkg/$cls")
        }
        persistPending()
        if (n > 0) updateNotification("锁屏延迟到点，已锁 $n 个界面")
    }

    /**
     * 当前前台包名。
     * 走 Shizuku shell 读 dumpsys window（不依赖"使用情况访问"权限），
     * 失败回退 dumpsys activity。
     */
    private fun currentForeground(): String {
        if (!ShizukuManager.granted()) return ""
        runCatching {
            val r = ShizukuManager.run(
                "sh", "-c",
                "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'"
            )
            parseFocus(r.output).ifEmpty {
                val r2 = ShizukuManager.run(
                    "sh", "-c",
                    "dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|ResumedActivity'"
                )
                parseFocus(r2.output)
            }
        }.getOrDefault("").let { if (it.isNotEmpty()) return it }
        return ""
    }

    /** 从 "mCurrentFocus=Window{... u0 com.xxx/com.xxx.Act}" 之类文本里提取包名。 */
    private fun parseFocus(text: String): String {
        for (raw in text.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            var rest = if (eq >= 0) line.substring(eq + 1) else line
            rest = rest.replace("{", " ").replace("}", " ")
            for (tok0 in rest.split(Regex("\\s+"))) {
                var tok = tok0
                if (tok.isEmpty()) continue
                val slash = tok.indexOf('/')
                if (slash > 0) tok = tok.substring(0, slash)
                if (isPackageName(tok)) return tok
            }
        }
        return ""
    }

    private fun isPackageName(s: String): Boolean {
        if (s.isEmpty() || s.indexOf('.') <= 0) return false
        if (s.contains(':') || s.contains('-')) return false
        for (c in s) {
            val ok = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_'
            if (!ok) return false
        }
        return s[0] in 'a'..'z' || s[0] in 'A'..'Z'
    }

    private fun noticeText(): String {
        val n = AutoRelock.targetCount
        return if (n == 0) "名单为空" else "守护 $n 个目标，待锁 ${AutoRelock.pendingCount} 个"
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "自动锁闭", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("自动锁闭")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, buildNotification(text))
        }
    }

    companion object {
        private const val CHANNEL_ID = "autorelock"
        private const val NOTIFY_ID = 3002
        private const val TICK_MS = 1000L

        /** 去重节流窗口（文档 §13.3）：同一目标 3 秒内只执行一次上锁。 */
        private const val THROTTLE_MS = 3000L

        /** 按配置启动/停止守护服务。任何异常都不能影响调用方。 */
        fun sync(ctx: Context) {
            runCatching {
                AppStore.ensureInit(ctx)
                AutoRelock.init(ctx)
                if (AutoRelock.active()) {
                    ctx.startForegroundService(Intent(ctx, AutoRelockService::class.java))
                } else {
                    ctx.stopService(Intent(ctx, AutoRelockService::class.java))
                }
            }
        }
    }
}
