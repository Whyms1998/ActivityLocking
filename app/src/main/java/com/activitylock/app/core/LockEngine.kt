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
 * 若规则带跳转深链（redirect），只能走拦截模式——组件禁用发生在包管理层，
 * 拦截器根本感知不到启动请求，无法实现"点图标跳转搜索页"。
 */
object LockEngine {

    suspend fun apply(pkg: String, cls: String, lock: Boolean, redirectUri: String? = null): LockOutcome =
        withContext(Dispatchers.IO) {
            if (lock) lock(pkg, cls, redirectUri) else unlock(pkg, cls)
        }

    /**
     * 「保存跳转目标」的唯一入口 —— 保证填进去的跳转**真的能生效**。
     *
     * ★ 为什么不能只写字段：跳转只在「拦截模式（block）+ 组件保持启用」下成立。
     *   - 组件被 `pm disable-user` 禁用时，点图标根本不会产生启动请求，
     *     拦截器（IActivityController）无从感知，自然也跳不了；
     *   - 规则 block=false 时 [AmsController.writeRules] 不会把它下发给 Watcher。
     *   此前「编辑界面」只调 `AppStore.updateRedirect()`，于是对一条 pm 模式的
     *   条目补填跳转 = **静默无效**（UI 显示已保存，实际点图标依旧无反应）。
     *
     * 本方法按需重锁一次把它切到拦截模式；不需要重锁时仅同步字段 + 下发规则。
     *
     * @return null 表示无需切换模式（仅更新字段）；否则返回重锁结果，
     *         调用方可据此提示失败原因。
     */
    suspend fun saveRedirect(pkg: String, cls: String, uri: String?): LockOutcome? {
        val clean = uri?.trim()?.takeIf { it.isNotBlank() }
        val rule = AppStore.rules.firstOrNull { it.packageName == pkg && it.activityName == cls }
        AppStore.updateRedirect(pkg, cls, clean)
        // 只有「已锁定 + 填了跳转 + 当前不是拦截模式（或组件仍被禁用）」才需要重锁
        val needsRelock = clean != null && rule != null && rule.locked &&
            (!rule.block || AppStore.isDisabled(pkg, cls))
        if (!needsRelock) {
            AmsController.writeRules()
            return null
        }
        return apply(pkg, cls, true, clean)
    }

    /**
     * 跳转目标：显式传入优先，否则沿用收藏夹里已存的。
     *
     * ★ 两处都必须把**空串**过滤成 null：历史数据里存在 `redirectUri = ""`
     *   （或 `writeRules` 下发的空值）的条目，若原样返回空串，[lock] 会以为
     *   「有跳转」而走 [lockWithRedirect]，把一条纯拦截条目错误地切到拦截模式，
     *   并给 Watcher 下发 `r=""` 的规则。
     */
    private fun effectiveRedirect(pkg: String, cls: String, redirectUri: String?): String? =
        redirectUri?.trim()?.takeIf { it.isNotBlank() }
            ?: AppStore.rules.firstOrNull { it.packageName == pkg && it.activityName == cls }
                ?.redirectUri?.trim()?.takeIf { it.isNotBlank() }

    private fun lock(pkg: String, cls: String, redirectUri: String?): LockOutcome {
        val redirect = effectiveRedirect(pkg, cls, redirectUri)
        if (redirect != null) return lockWithRedirect(pkg, cls, redirect)

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
        return lockByController(pkg, cls)
    }

    /** 拦截 + 跳转模式：组件必须保持启用，让拦截器看到启动请求后拒绝并拉起深链。 */
    private fun lockWithRedirect(pkg: String, cls: String, redirect: String): LockOutcome {
        if (!ShizukuManager.granted()) {
            return LockOutcome(false, "none", "Shizuku 不可用，无法启用拦截")
        }
        // 之前可能被 pm 禁用过：先恢复组件，否则拦截器收不到启动请求
        if (AppStore.isDisabled(pkg, cls)) {
            ShizukuManager.enable(pkg, cls)
        }
        AppStore.addRule(pkg, cls, block = true, redirectUri = redirect)
        AmsController.writeRules()
        if (AmsController.ensureStarted()) {
            ShizukuManager.run("am", "force-stop", pkg)
            AppStore.recordHistory(pkg, cls, "block", "LOCK")
            return LockOutcome(true, "block", "已启用拦截并跳转：$redirect")
        }
        AppStore.removeRule(pkg, cls)
        AmsController.writeRules()
        return LockOutcome(false, "none", "拦截进程启动失败")
    }

    private fun lockByController(pkg: String, cls: String): LockOutcome {
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
        // ★ v1.31.0：次数限额计数点迁移——旧「验证放行 +1」删除（覆盖不了跳转直达，
        //   且工作台/场景/磁贴解锁都会误触发）。新口径 = UsageTracker.todayOpenCount
        //   系统事件统计「入口界面 RESUMED」，由 QuotaService 巡检统一判定超额。
        // 通知自动锁闭记账：该界面已解锁，若开启「解锁后重新锁」则开始计时
        runCatching { AutoRelock.noteUnlocked(pkg, cls) }
        return LockOutcome(false, "pm", "已解锁（条目保留在收藏夹）")
    }
}
