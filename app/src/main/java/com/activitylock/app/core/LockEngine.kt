package com.activitylock.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LockOutcome(
    val locked: Boolean,
    val mode: String,   // "pm" 组件禁用 | "block" 系统级拦截 | "none"
    val detail: String,
)

/**
 * 锁定引擎：优先组件禁用（pm disable-user）；
 * 被 HyperOS 等系统限制时，降级为系统级 Activity 启动拦截（ActivityController）。
 */
object LockEngine {

    suspend fun apply(pkg: String, cls: String, lock: Boolean): LockOutcome =
        withContext(Dispatchers.IO) {
            if (lock) lock(pkg, cls) else unlock(pkg, cls)
        }

    private fun lock(pkg: String, cls: String): LockOutcome {
        val comp = "$pkg/$cls"
        for (cmd in listOf(
            arrayOf("pm", "disable-user", "--user", "0", comp),
            arrayOf("pm", "disable-user", comp),
            arrayOf("pm", "disable", "--user", "0", comp),
            arrayOf("pm", "disable", comp),
        )) {
            val r = ShizukuManager.run(*cmd)
            if (r.code == 0 && !r.output.contains("Exception", true) && AppStore.isDisabled(pkg, cls)) {
                AppStore.addRule(pkg, cls)
                AmsController.writeRules()
                // 清掉目标 App 的残留任务栈，否则点图标只是"恢复"已有任务，不会触发启动拦截
                ShizukuManager.run("am", "force-stop", pkg)
                AppStore.recordHistory(pkg, cls, "pm", "LOCK")
                return LockOutcome(true, "pm", "已禁用组件")
            }
        }

        // 组件禁用被系统限制（HyperOS 封锁 shell 修改组件状态）→ 系统级拦截
        if (ShizukuManager.granted()) {
            AppStore.addRule(pkg, cls, block = true)
            AmsController.writeRules()
            if (AmsController.ensureStarted()) {
                // 关键：清残留任务栈，让下次打开必然走"启动"路径从而被拦截
                ShizukuManager.run("am", "force-stop", pkg)
                AppStore.recordHistory(pkg, cls, "block", "LOCK")
                return LockOutcome(true, "block", "系统级拦截已启用")
            }
            AppStore.removeRule(pkg, cls)
            AmsController.writeRules()
        }
        return LockOutcome(false, "none", "Shizuku 不可用，无法启用拦截")
    }

    private fun unlock(pkg: String, cls: String): LockOutcome {
        // 解锁但保留收藏条目（locked=false），下次可一键再锁
        if (AppStore.isDisabled(pkg, cls)) {
            for (cmd in listOf(
                arrayOf("pm", "enable", "--user", "0", "$pkg/$cls"),
                arrayOf("pm", "enable", "$pkg/$cls"),
            )) {
                val r = ShizukuManager.run(*cmd)
                if (r.code == 0 && !r.output.contains("Exception", true)) break
            }
        }
        AppStore.updateLocked(pkg, cls, false)
        AmsController.writeRules()
        AppStore.recordHistory(pkg, cls, "pm", "UNLOCK")
        return LockOutcome(false, "pm", "已解锁（条目保留在收藏夹）")
    }
}
