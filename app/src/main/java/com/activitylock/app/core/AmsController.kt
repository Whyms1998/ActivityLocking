package com.activitylock.app.core

import android.content.pm.PackageManager
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.File

/**
 * 系统级拦截引擎：
 * 通过 Shizuku 启动 shell 身份的 app_process 常驻进程（WatcherMain），
 * 该进程注册 IActivityManager.setActivityController（Android 预留的全局启动控制器），
 * 使被锁组件在启动时被系统直接拒绝。
 * 锁定清单通过外部存储上的 blocked.json 传递（App 写入，shell 读取）。
 */
object AmsController {

    fun rulesFile(): File {
        val ctx = AppStore.context()
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(dir, "blocked.json")
    }

    /** 把当前生效的拦截规则同步给常驻 Watcher（App 写文件，shell 读文件）。 */
    fun writeRules() {
        runCatching {
            val comps = AppStore.rules
                .filter { it.block && it.locked }
                .map { "${it.packageName}/${it.activityName}" }
            val f = rulesFile()
            f.parentFile?.mkdirs()
            f.writeText(org.json.JSONArray(comps).toString())
        }
    }

    fun isRunning(): Boolean = runCatching {
        // 用字符类结尾避免 pgrep -f 匹配到自身命令行（sh -c 的参数里含同样字符串）
        val r = ShizukuManager.run("pgrep", "-f", "com.activitylock.app.watcher.WatcherMai[n]")
        r.code == 0 && !r.output.contains("SecurityException", true)
    }.getOrDefault(false)

    /** 启动常驻 Watcher（若未运行）。 */
    fun ensureStarted(): Boolean {
        if (isRunning()) return true
        if (!ShizukuManager.installed() || !ShizukuManager.granted()) return false
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
            Thread.sleep(800)
            isRunning()
        }.getOrDefault(false)
    }

    /** 拦截是否真正生效（watcher 运行中即视为生效）。 */
    fun isActive(): Boolean = isRunning()
}
