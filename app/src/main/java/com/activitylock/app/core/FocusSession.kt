package com.activitylock.app.core

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 专注会话：临时性地以包级拦截一层“会话目标”，到时自动恢复。
 * 与常驻规则（c 条目）、超额拦截（p 条目）互为独立层，writeRules 统一合并。
 *
 * 状态机：idle → start(写 targets + 清栈 + 启动服务) → running（Watcher 持续整包拦截）
 *       → endAt（服务倒计时/Alarm/打开 App 对账，任一先到）→ end(清 targets → 自动恢复原状)
 *
 * 模式：
 *  - selected  锁定勾选的 App
 *  - whitelist 锁定除白名单外的全部第三方应用（自动排除：本 App、桌面、系统应用）
 */
@Serializable
data class FocusSession(
    val id: String,
    val mode: String,               // "selected" | "whitelist"
    val targets: List<String>,      // 本次会话要整包拦截的包名（启动时计算并固化）
    val durationMin: Int,
    val startedAt: Long,
    val endAt: Long,
    val strict: Boolean = false,    // 提前结束需要通过验证（无验证配置时强制答题）
)

@Serializable
private class FocusStateData(
    val session: FocusSession? = null,
)

object FocusState {

    private fun file(ctx: Context) = File(ctx.filesDir, "focus_state.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    // 注：原先这里打了 3 处 "FocusDebug" 日志，而 focusPackages() 会被
    // AmsController.writeRules() 顺带调用（写拦截规则时），属于高频路径，
    // 正式版已移除——每轮写规则都刷日志既拖慢 IO 也污染 logcat。
    fun load(ctx: Context): FocusSession? = runCatching {
        val f = file(ctx)
        if (!f.exists()) return null
        json.decodeFromString<FocusStateData>(f.readText()).session
    }.getOrNull()

    fun save(ctx: Context, session: FocusSession?) {
        runCatching {
            file(ctx).writeText(json.encodeToString(FocusStateData(session)))
        }
    }

    /** 会话目标包名集合（会话进行中时非空）。 */
    fun focusPackages(ctx: Context): Set<String> =
        load(ctx)?.targets?.toSet() ?: emptySet()

    /** 上次会话的配置（一键重复用）。 */
    @Serializable
    data class LastConfig(
        val mode: String,
        val members: List<String>,
        val durationMin: Int,
        val strict: Boolean,
    )

    private fun configPrefs(ctx: Context) =
        ctx.getSharedPreferences("focus_last", Context.MODE_PRIVATE)

    fun saveLastConfig(ctx: Context, cfg: LastConfig) {
        configPrefs(ctx).edit()
            .putString("cfg", json.encodeToString(cfg))
            .apply()
    }

    fun lastConfig(ctx: Context): LastConfig? = runCatching {
        val raw = configPrefs(ctx).getString("cfg", null) ?: return null
        json.decodeFromString<LastConfig>(raw)
    }.getOrNull()

    /** 记录一次提前结束（破功回流用）。 */
    fun recordEarlyEnd(ctx: Context) {
        configPrefs(ctx).edit().putLong("last_early_end", System.currentTimeMillis()).apply()
        StreakStore.markBroken(ctx) // 专注破功
    }

    fun lastEarlyEnd(ctx: Context): Long =
        configPrefs(ctx).getLong("last_early_end", 0)
}

object FocusEngine {

    const val SELF_PKG = "com.activitylock.app"

    /** 白名单模式下不拦截的包：本 App、桌面、系统应用（只拦用户安装的第三方）。 */
    fun computeWhitelistTargets(ctx: Context, whitelist: Set<String>): List<String> {
        val pm = ctx.packageManager
        val homePkgs = runCatching {
            pm.queryIntentActivities(Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)
                .mapNotNull { it.activityInfo?.applicationInfo?.packageName }
        }.getOrDefault(emptyList())
        return pm.getInstalledPackages(0)
            .mapNotNull { it.packageName }
            .filter { pkg ->
                pkg != SELF_PKG &&
                    pkg !in whitelist &&
                    pkg !in homePkgs &&
                    runCatching {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                    }.getOrDefault(false)
            }
    }

    /**
     * 启动会话：固化目标 → 写拦截层 → 清栈 → 启动倒计时服务。返回 null=成功，否则为错误说明。
     *
     * ★ 必须是 suspend 且切到 IO 线程执行（原先是从 Compose 点击回调里直接同步调用）：
     *  - whitelist 模式要枚举全部已安装应用（getInstalledPackages + 逐包
     *    getApplicationInfo，上百次跨进程调用）；
     *  - 之后还要对每个目标包跑一次 Shizuku `am force-stop`（每包一次 IPC + 子进程启动）。
     *  在主线程跑会阻塞数秒至数十秒 → 必然 ANR。
     */
    suspend fun start(
        ctx: Context,
        mode: String,
        members: Set<String>,   // selected 模式=要锁的包；whitelist 模式=白名单（允许的包）
        durationMin: Int,
        strict: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        if (durationMin !in 1..1440) return@withContext "时长无效"
        val targets = when (mode) {
            "whitelist" -> computeWhitelistTargets(ctx, members)
            else -> members.filter { it != SELF_PKG }
        }
        if (targets.isEmpty()) return@withContext "没有要拦截的目标"
        val now = System.currentTimeMillis()
        val session = FocusSession(
            id = java.util.UUID.randomUUID().toString(),
            mode = mode,
            targets = targets,
            durationMin = durationMin,
            startedAt = now,
            endAt = now + durationMin * 60_000L,
            strict = strict,
        )
        FocusState.save(ctx, session)
        FocusState.saveLastConfig(ctx, FocusState.LastConfig(mode, members.toList(), durationMin, strict))
        AmsController.writeRules()
        // 清栈：正在用的目标 App 立即停下
        for (pkg in targets) ShizukuManager.run("am", "force-stop", pkg)
        com.activitylock.app.core.FocusService.start(ctx)
        null
    }

    /** 结束会话：清会话层 → 自动恢复原状 → 停服务 → 完成通知。 */
    fun end(ctx: Context, session: FocusSession, completed: Boolean) {
        FocusState.save(ctx, null)
        if (!completed) FocusState.recordEarlyEnd(ctx)
        AmsController.writeRules()
        com.activitylock.app.core.FocusService.stop(ctx)
        val msg = if (completed) "专注完成，已恢复全部 App" else "专注已提前结束"
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        runCatching {
            // ★ 必须复用已注册的渠道：原来用 "focus_watch" 这个从未创建过的渠道，
            //   Android 8+ 会直接丢弃该通知（logcat: No Channel found）——用户永远
            //   收不到「专注完成」提示。同时 id 3001 与 AutoFreezeService 撞车，改为 3004。
            FocusService.ensureChannel(ctx)
            nm.notify(
                3004,
                android.app.Notification.Builder(ctx, FocusService.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(msg)
                    .setContentText("专注 ${session.durationMin} 分钟")
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    /** 对账：会话到时未结束则结束。由服务倒计时/打开 App/巡检共同调用，幂等。 */
    fun reconcile(ctx: Context) {
        AppStore.ensureInit(ctx)
        val session = FocusState.load(ctx) ?: return
        if (System.currentTimeMillis() >= session.endAt) {
            end(ctx, session, completed = true)
        }
    }
}
