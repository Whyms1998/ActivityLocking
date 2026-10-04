package com.activitylock.app.core

import android.content.Context
import java.security.MessageDigest

/**
 * 自律码：启用严格模式/防卸载等"强承诺"功能时生成一次，
 * 展示一次后只存哈希；之后关闭这些功能必须输入自律码。
 * 用户应把它抄写到别处（发给朋友/写在纸上）——这是"未来的自己给现在的自己设的锁"。
 * 丢失的后果：等待当前会话/计划自然结束（不造成死锁）。
 */
object SelfCode {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("self_code", Context.MODE_PRIVATE)

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun hasCode(ctx: Context): Boolean = prefs(ctx).contains("hash")

    fun verify(ctx: Context, input: String): Boolean {
        val hash = prefs(ctx).getString("hash", null) ?: return true
        return hash == sha256(input.trim())
    }

    /** 设置自律码（覆盖旧码）。返回 false 表示输入无效（少于 4 位）。 */
    fun set(ctx: Context, code: String): Boolean {
        val clean = code.trim()
        if (clean.length < 4) return false
        prefs(ctx).edit().putString("hash", sha256(clean)).apply()
        return true
    }
}
