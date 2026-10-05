package com.activitylock.app.core

import android.content.Context

/**
 * 临时解锁（全局）：激活后 Watcher 放行所有启动，截止时间到自动失效。
 * 由磁贴/验证页触发，需通过所有锁定界面的最强解锁条件。
 */
object UnlockExemptions {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("exempt_state", Context.MODE_PRIVATE)

    /** 当前豁免截止时间（epoch ms），0 = 无。 */
    fun until(ctx: Context): Long =
        prefs(ctx).getLong("until", 0)

    /** 激活全局临时解锁。 */
    fun addGlobal(ctx: Context, untilMs: Long) {
        prefs(ctx).edit().putLong("until", untilMs).apply()
    }

    /** 立即恢复拦截（清除豁免）。 */
    fun removeGlobal(ctx: Context) {
        prefs(ctx).edit().putLong("until", 0).apply()
    }
}
