package com.activitylock.app.core

import android.content.pm.PackageManager
import android.util.Log
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
    private const val HEARTBEAT = "/data/local/tmp/al_watcher.hb"

    /** 心跳超过这个间隔没更新就判定主循环停摆（心跳周期 60 秒，留 3 倍容错）。 */
    private const val STALL_MS = 180_000L

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
            // ★ v1.32.0：拦截集合改由监督判定引擎给出（门禁式，每轮重算，无「超额残留」问题）。
            //   整包对象 → 包级条目 {"p":pkg}；单界面对象 → 组件条目 {"c":"pkg/cls"}。
            //   两种都不依赖该 App 是否在 rules 里，因此「只监督、不锁定」也能拦。
            val blocks = SuperviseEngine.blocks(ctx)
            val blockedPkgs = blocks.filter { it.isWholeApp }.map { it.pkg }.toSet()
            val blockedComps = blocks.filterNot { it.isWholeApp }
                .map { "${it.pkg}/${it.cls}" }.toSet()
            val arr = org.json.JSONArray()
            AppStore.rules
                .filter { it.block && it.locked }
                .forEach { r ->
                    val comp = "${r.packageName}/${r.activityName}"
                    val redirect = when {
                        // 超额优先：整包或该界面被监督拦下 → 跳额度说明页
                        r.packageName in blockedPkgs -> QUOTA_GATE_COMPONENT
                        comp in blockedComps -> QUOTA_GATE_COMPONENT
                        else -> r.redirectUri ?: ""
                    }
                    val entry = org.json.JSONObject()
                        .put("c", comp)
                        .put("r", redirect)
                    // 到期解锁：带截止时间，Watcher 过期后自动放行
                    if (r.lockUntil > 0) entry.put("u", r.lockUntil)
                    arr.put(entry)
                }
            // 监督拦截：整包与单界面两种粒度
            for (b in blocks) {
                arr.put(
                    if (b.isWholeApp) org.json.JSONObject().put("p", b.pkg).put("r", QUOTA_GATE_COMPONENT)
                    else org.json.JSONObject().put("c", "${b.pkg}/${b.cls}").put("r", QUOTA_GATE_COMPONENT)
                )
            }
            // 专注会话层：会话进行中目标 App 整包静默拦截
            // （已被监督拦下的包跳过——监督的说明页比静默拦截更能解释原因）
            val focus = FocusState.focusPackages(ctx)
            for (pkg in focus) {
                if (pkg in blockedPkgs) continue
                if (blocks.any { it.pkg == pkg }) continue
                arr.put(
                    org.json.JSONObject()
                        .put("p", pkg)
                        .put("r", "")
                )
            }
            // 到点跳转层：连续使用 N 分钟 → 拉起学习 App
            // 已被监督拦截的包跳过（超额优先）
            for (c in AppStore.sessionCfgs) {
                if (!c.enabled || c.pkg in focus) continue
                if (blocks.any { it.pkg == c.pkg }) continue
                // lock 模式纯锁定无跳转：t 留空也要下发
                val resolved = if (c.mode == "lock") "" else resolveSessionTarget(c.target) ?: continue
                arr.put(
                    org.json.JSONObject()
                        .put("f", c.pkg)
                        .put("s", c.limitMin * 60)
                        .put("t", resolved)
                        .put("n", c.maxJumps)
                        .put("g", c.graceSec)
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

    /**
     * 看门狗（★ v1.32.2）：**进程活着 ≠ 主循环还在转**。
     *
     * 真机实测：watcher 的 tick 在某一刻彻底停住（日志 5 分钟无新增），进程仍在
     * `ps` 里、旧规则照样拦，但 `reloadRules()` 不再执行 —— 于是「改了设置永远
     * 不生效」，而且没有任何提示。心跳由主循环每 60 秒写一次时间戳，停摆即失联。
     *
     * @return true 表示健康（或已修复/无需判断）
     */
    fun checkHeartbeat(): Boolean = runCatching {
        if (!isRunning()) return@runCatching true
        val hb = java.io.File(HEARTBEAT)
        // 没有心跳文件 = 还没升到 v25 的旧进程，不做判断（升级流程会自己换掉它）
        if (!hb.exists()) return@runCatching true
        val beat = hb.readText().trim().toLongOrNull() ?: return@runCatching true
        val age = System.currentTimeMillis() - beat
        if (age in 0..STALL_MS) return@runCatching true
        Log.w("AmsController", "watcher 主循环停摆 ${age / 1000}s，重启")
        killWatcher()
        ensureStarted()
    }.getOrDefault(true)
}
