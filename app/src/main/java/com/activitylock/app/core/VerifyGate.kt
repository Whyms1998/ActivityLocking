package com.activitylock.app.core

import android.content.Context

/**
 * 解锁验证配置：为自律服务，解锁前要求等待一段时间或输入指定密码。
 * 支持两级配置：全局默认 + 按界面（规则）覆盖。
 */
object VerifyGate {

    private const val NAME = "unlock_verify"
    const val METHOD_DELAY = "delay"
    const val METHOD_PASSWORD = "password"
    const val METHOD_QUIZ = "quiz"
    const val METHOD_RANDOM = "random"
    const val MODE_NONE = "none"
    // "跟随全局"用 verifyMode = null 表示（无常量）

    /** 一次解锁需要满足的条件。passwords 为可接受的密码集合（全部解锁时可能涉及多个界面各自的密码）。 */
    data class Requirement(
        val mode: String,          // "none" | "delay" | "password" | "quiz" | "random"
        val delaySec: Int,
        val passwords: List<String>,
        val quizCount: Int = 1,    // 答题需连对的题数
        val randomDigits: Int = 6, // 随机密码位数
    ) {
        val isNone: Boolean get() = mode == MODE_NONE
        fun passwordOk(input: String): Boolean =
            passwords.isNotEmpty() && passwords.any { it == input }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    fun method(ctx: Context): String = prefs(ctx).getString("method", METHOD_DELAY)!!

    fun delaySeconds(ctx: Context): Int = prefs(ctx).getInt("delay_sec", 5)

    fun password(ctx: Context): String = prefs(ctx).getString("password", "") ?: ""

    fun save(ctx: Context, enabled: Boolean, method: String, password: String, delaySec: Int, quizCount: Int = 1) {
        prefs(ctx).edit()
            .putBoolean("enabled", enabled)
            .putString("method", method)
            .putString("password", password)
            .putInt("delay_sec", delaySec)
            .putInt("quiz_count", quizCount.coerceIn(1, 10))
            .apply()
    }

    /** 全局答题数量（1-10 题）。 */
    fun quizCount(ctx: Context): Int = prefs(ctx).getInt("quiz_count", 1).coerceIn(1, 10)

    fun setQuizCount(ctx: Context, count: Int) {
        prefs(ctx).edit().putInt("quiz_count", count.coerceIn(1, 10)).apply()
    }

    /** 随机密码位数（4-12 位），默认 6。 */
    fun randomDigits(ctx: Context): Int = prefs(ctx).getInt("random_digits", 6).coerceIn(4, 12)

    fun setRandomDigits(ctx: Context, digits: Int) {
        prefs(ctx).edit().putInt("random_digits", digits.coerceIn(4, 12)).apply()
    }

    // ---- 解锁时间范围：只在设定时段内允许通过验证解锁（自律约束，跨午夜支持）----

    fun unlockWindowEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("unlock_window_enabled", false)

    fun unlockWindowStart(ctx: Context): Int = prefs(ctx).getInt("unlock_window_start", 22 * 60)

    fun unlockWindowEnd(ctx: Context): Int = prefs(ctx).getInt("unlock_window_end", 7 * 60)

    fun saveUnlockWindow(ctx: Context, enabled: Boolean, startMin: Int, endMin: Int) {
        prefs(ctx).edit()
            .putBoolean("unlock_window_enabled", enabled)
            .putInt("unlock_window_start", startMin.coerceIn(0, 24 * 60 - 1))
            .putInt("unlock_window_end", endMin.coerceIn(0, 24 * 60 - 1))
            .apply()
    }

    /** 当前时刻是否在允许解锁的时段内（start==end 视为全天允许）。 */
    fun inUnlockWindow(ctx: Context): Boolean {
        if (!unlockWindowEnabled(ctx)) return true
        val start = unlockWindowStart(ctx)
        val end = unlockWindowEnd(ctx)
        if (start == end) return true
        val cal = java.util.Calendar.getInstance()
        val m = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return if (start < end) m in start until end
        else m >= start || m < end
    }

    /** 不在允许解锁时段 → 验证一律不通过。 */
    fun outsideUnlockWindow(ctx: Context): Boolean = !inUnlockWindow(ctx)

    /** 时段文案，如 "22:00-07:00"。 */
    fun unlockWindowDesc(ctx: Context): String {
        fun fmt(min: Int): String =
            "%02d:%02d".format(min / 60, min % 60)
        return fmt(unlockWindowStart(ctx)) + "-" + fmt(unlockWindowEnd(ctx))
    }

    /** 生成指定位数的随机密码（首位非零）。 */
    fun genRandomCode(digits: Int): String {
        val r = java.util.Random()
        val sb = StringBuilder()
        sb.append(r.nextInt(9) + 1)
        repeat(digits - 1) { sb.append(r.nextInt(10)) }
        return sb.toString()
    }

    /** 全局配置解析为条件（全局开关关闭时为 none）。 */
    fun globalRequirement(ctx: Context): Requirement =
        if (isEnabled(ctx)) {
            val pwd = password(ctx)
            when {
                method(ctx) == METHOD_PASSWORD && pwd.isBlank() ->
                    Requirement(METHOD_DELAY, delaySeconds(ctx).coerceAtLeast(1), emptyList())
                method(ctx) == METHOD_QUIZ ->
                    Requirement(METHOD_QUIZ, 0, emptyList(), quizCount = quizCount(ctx))
                method(ctx) == METHOD_RANDOM ->
                    Requirement(METHOD_RANDOM, 0, emptyList(), randomDigits = randomDigits(ctx))
                else ->
                    Requirement(method(ctx), delaySeconds(ctx).coerceIn(5, 3600), listOf(pwd))
            }
        } else Requirement(MODE_NONE, 0, emptyList())

    /**
     * 某条规则的有效解锁条件：
     * 规则未单独设置（verifyMode=null）→ 跟随全局；
     * 单独设置 none/delay/password/quiz → 以规则为准（未填参数时回退全局参数）。
     */
    fun requirementFor(ctx: Context, rule: LockRule?): Requirement {
        val ruleMode = rule?.verifyMode
        val mode = when (ruleMode) {
            MODE_NONE -> MODE_NONE
            METHOD_DELAY -> METHOD_DELAY
            METHOD_PASSWORD -> METHOD_PASSWORD
            METHOD_QUIZ -> METHOD_QUIZ
            else -> return globalRequirement(ctx)
        }
        if (mode == MODE_NONE) return Requirement(MODE_NONE, 0, emptyList())
        if (mode == METHOD_QUIZ) return Requirement(METHOD_QUIZ, 0, emptyList(), rule?.quizCount ?: quizCount(ctx))
        if (mode == METHOD_RANDOM) return Requirement(METHOD_RANDOM, 0, emptyList(), randomDigits = randomDigits(ctx))
        val delaySec = (rule?.verifyDelaySec ?: delaySeconds(ctx)).coerceIn(5, 3600)
        val pwd = rule?.verifyPassword?.takeIf { it.isNotBlank() } ?: password(ctx)
        return if (mode == METHOD_PASSWORD && pwd.isBlank()) {
            // 规则选了密码但没填密码、全局也没密码 → 退化为等待
            Requirement(METHOD_DELAY, delaySec, emptyList())
        } else {
            Requirement(mode, delaySec, listOf(pwd))
        }
    }

    /**
     * 批量操作（磁贴「全部解锁」）的条件：取所有相关界面的最强要求——
     * 密码 > 答题 > 等待；密码可接受其中任意一个，等待取最长。
     * 防止绕过：给某界面单独设了密码，磁贴不能只用全局倒计时放过它。
     */
    fun bulkRequirement(ctx: Context, rules: List<LockRule>): Requirement {
        val reqs = buildList {
            add(globalRequirement(ctx))
            rules.filter { it.locked }.forEach { add(requirementFor(ctx, it)) }
        }
        if (reqs.any { it.mode == METHOD_RANDOM }) {
            return Requirement(METHOD_RANDOM, 0, emptyList(),
                randomDigits = reqs.filter { it.mode == METHOD_RANDOM }.maxOf { it.randomDigits })
        }
        if (reqs.any { it.mode == METHOD_PASSWORD }) {
            val pwds = reqs.filter { it.mode == METHOD_PASSWORD }
                .flatMap { it.passwords }.filter { it.isNotBlank() }.distinct()
            val maxDelay = reqs.maxOf { it.delaySec }
            return if (pwds.isEmpty()) {
                Requirement(METHOD_DELAY, maxDelay.coerceAtLeast(1), emptyList())
            } else {
                Requirement(METHOD_PASSWORD, maxDelay, pwds)
            }
        }
        if (reqs.any { it.mode == METHOD_QUIZ }) {
            return Requirement(METHOD_QUIZ, 0, emptyList(),
                quizCount = reqs.filter { it.mode == METHOD_QUIZ }.maxOf { it.quizCount })
        }
        val maxDelay = reqs.filter { it.mode == METHOD_DELAY }.maxOfOrNull { it.delaySec } ?: 0
        return if (maxDelay > 0) Requirement(METHOD_DELAY, maxDelay, emptyList())
        else Requirement(MODE_NONE, 0, emptyList())
    }

    /** 条件强度排序：用于判断一次修改是否属于"降低门槛"。 */
    fun strength(mode: String): Int = when (mode) {
        METHOD_RANDOM -> 4
        METHOD_PASSWORD -> 3
        METHOD_QUIZ -> 2
        METHOD_DELAY -> 1
        else -> 0
    }

    /** 生成一道难度适中的口算题（题面，答案）。 */
    fun genQuiz(): Pair<String, Int> {
        val r = java.util.Random()
        return when (r.nextInt(3)) {
            0 -> {
                val a = r.nextInt(80) + 12
                val b = r.nextInt(9) + 2
                "$a × $b = ?" to (a * b)
            }
            1 -> {
                val a = r.nextInt(500) + 120
                val b = r.nextInt(200) + 40
                "$a + $b = ?" to (a + b)
            }
            else -> {
                val a = r.nextInt(400) + 220
                val b = r.nextInt(150) + 40
                "$a − $b = ?" to (a - b)
            }
        }
    }
}
