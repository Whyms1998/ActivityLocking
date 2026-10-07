package com.activitylock.app.core

import android.content.pm.PackageManager
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.File

/**
 * 系统级拦截引擎：
 * 通过 Shizuku 启动 shell 身份的 app_process 常驻进程（WatcherMain），
 * 该进程注册 IActivityManager.setActivityController（Android 预留的全局启动控制器），
 * 使被锁组件在启动时被系统直接拒绝；带跳转深链的规则会在拦截的同时拉起深链。
 * 锁定清单通过外部存储上的 blocked.json 传递（App 写入，shell 读取）。
 */
object AmsController {

    private val WATCHER_VERSION = WatcherVersion.VERSION
    private const val VERSION_MARKER = "/data/local/tmp/al_watcher.ver"

    fun rulesFile(): File {
        val ctx = AppStore.context()
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(dir, "blocked.json")
    }

    /**
     * 把当前生效的拦截规则同步给常驻 Watcher（App 写文件，shell 读文件）。
     * 格式：[{"c":"包名/类名","r":"跳转深链（可为空串）"}]
     * 已超额 App 的跳转目标替换为额度提示页（点图标能看到"额度用完"而不是没反应）。
     */
    fun writeRules() {
        runCatching {
            val ctx = AppStore.context()
            val over = QuotaState.overPackages(ctx)
            val arr = org.json.JSONArray()
            AppStore.rules
                .filter { it.block && it.locked }
                .forEach { r ->
                    val redirect = when {
                        r.packageName in over -> QUOTA_GATE_COMPONENT
                        else -> r.redirectUri ?: ""
                    }
                    val entry = org.json.JSONObject()
                        .put("c", "${r.packageName}/${r.activityName}")
                        .put("r", redirect)
                    // 到期解锁：带截止时间，Watcher 过期后自动放行
                    if (r.lockUntil > 0) entry.put("u", r.lockUntil)
                    arr.put(entry)
                }
            // 包级超额拦截：额度用完的 App 整包拒绝（无论规则解锁与否、无论哪个界面）
            for (pkg in over) {
                arr.put(
                    org.json.JSONObject()
                        .put("p", pkg)
                        .put("r", QUOTA_GATE_COMPONENT)
                )
            }
            // 专注会话层：会话进行中目标 App 整包静默拦截（已超额的保持额度页跳转）
            val focus = FocusState.focusPackages(ctx)
            for (pkg in focus) {
                if (over.contains(pkg)) continue
                arr.put(
                    org.json.JSONObject()
                        .put("p", pkg)
                        .put("r", "")
                )
            }
            // 到点跳转层：连续使用 N 分钟 → 拉起学习 App
            // 已超额/专注中的包跳过（它们有更严的整包拦截，无需软引导）
            for (c in AppStore.sessionCfgs) {
                if (!c.enabled || c.pkg in over || c.pkg in focus) continue
                // lock 模式纯锁定无跳转：t 留空也要下发
                val resolved = if (c.mode == "lock") "" else resolveSessionTarget(c.target) ?: continue
                arr.put(
                    org.json.JSONObject()
                        .put("f", c.pkg)
                        .put("s", c.limitMin * 60)
                        .put("t", resolved)
                        .put("n", c.maxJumps)
                        .put("m", c.mode)
                )
            }
            // 临时解锁层：全局豁免（锁定强度开启"计划窗口内禁用"时不激活）
            val exemptSuppressed = LockStrength.noExemptInSchedule(ctx) &&
                ScheduleEngine.anyActive(AppStore.schedules)
            val exemptUntil = UnlockExemptions.until(ctx)
            if (!exemptSuppressed && exemptUntil > System.currentTimeMillis()) {
                arr.put(
                    org.json.JSONObject()
                        .put("g", 1)
                        .put("u", exemptUntil)
                )
            }
            val f = rulesFile()
            f.parentFile?.mkdirs()
            f.writeText(arr.toString())
        }
    }

    /** 到点跳转目标解析：包名 → 启动组件（watcher 查不了 PM，App 侧解析好）；深链/组件名原样。 */
    private fun resolveSessionTarget(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.contains("://") || t.contains("/")) return t
        val ctx = AppStore.context()
        return runCatching {
            ctx.packageManager.getLaunchIntentForPackage(t)?.component?.flattenToString()
        }.getOrNull()
    }

    /** 超额提示页（本 App 内，exported，供 Watcher 以 shell 身份拉起）。 */
    const val QUOTA_GATE_COMPONENT = "com.activitylock.app/com.activitylock.app.QuotaGateActivity"

    fun isRunning(): Boolean = runCatching {
        // 用字符类结尾避免 pgrep -f 匹配到自身命令行（sh -c 的参数里含同样字符串）
        val r = ShizukuManager.run("pgrep", "-f", "com.activitylock.app.watcher.WatcherMai[n]")
        r.code == 0 && !r.output.contains("SecurityException", true)
    }.getOrDefault(false)

    /** 运行中的 Watcher 是否为当前版本（旧版本不识别跳转规则，需重启）。 */
    private fun watcherUpToDate(): Boolean = runCatching {
        ShizukuManager.run("cat", VERSION_MARKER).output.trim() == WATCHER_VERSION.toString()
    }.getOrDefault(false)

    private fun killWatcher() {
        // 直接传参执行（无 shell 引号层）；失败或仍有存活实例时按 PID 补刀
        runCatching {
            val r = ShizukuManager.run("pkill", "-f", "com.activitylock.app.watcher.WatcherMai[n]")
            if (r.code != 0) throw IllegalStateException(r.output)
        }
        Thread.sleep(300)
        runCatching {
            val out = ShizukuManager.run("pgrep", "-f", "com.activitylock.app.watcher.WatcherMai[n]").output
            out.split(Regex("\\s+"))
                .filter { it.toIntOrNull() != null }
                .forEach { ShizukuManager.run("kill", "-9", it) }
        }
        Thread.sleep(200)
    }

    /** 启动常驻 Watcher（若未运行或版本过旧）。 */
    fun ensureStarted(): Boolean {
        if (!ShizukuManager.installed() || !ShizukuManager.granted()) return false
        if (isRunning()) {
            if (watcherUpToDate()) return true
            killWatcher()
        }
        val apkPath = runCatching {
            val ctx = AppStore.context()
            ctx.packageManager.getApplicationInfo(ctx.packageName, 0).sourceDir
        }.getOrNull() ?: return false
        val script = "export CLASSPATH=$apkPath; " +
            "nohup app_process /system/bin com.activitylock.app.watcher.WatcherMain " +
            ">/dev/null 2>&1 &"
        return runCatching {
            val binder = Shizuku.getBinder()
            val svc = IShizukuService.Stub.asInterface(binder)
            val p = svc.newProcess(arrayOf("sh", "-c", script), null, null)
            p.waitFor()
            // 等新 Watcher 就绪（写入版本标记并清杀旧实例）
            repeat(6) {
                Thread.sleep(500)
                if (isRunning() && watcherUpToDate()) return true
            }
            isRunning()
        }.getOrDefault(false)
    }

    /** 拦截是否真正生效（watcher 运行中即视为生效）。 */
    fun isActive(): Boolean = isRunning()
}
