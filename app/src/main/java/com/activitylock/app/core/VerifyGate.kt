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

    /**
     * 「应用级解锁条件」的来源，由 [AppStore] 在 init 时注入。
     *
     * ★ 为什么不直接持有 RuleStore：本类的调用点极多（磁贴、临时解锁、
     *   场景、收藏夹、自动锁闭页…）且都是静态调用，用 provider 注入可以
     *   一次性拿到三级优先级链，无需改动全部调用点；返回 null 表示该应用
     *   没有设应用级条件（继续跟随全局）。
     */
    @Volatile
    private var appVerifyProvider: ((String) -> AppVerify?)? = null

    fun bindAppVerifyProvider(p: (String) -> AppVerify?) {
        appVerifyProvider = p
    }

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    fun method(ctx: Context): String = prefs(ctx).getString("method", METHOD_DELAY)!!

    fun delaySeconds(ctx: Context): Int = prefs(ctx).getInt("delay_sec", 5)

    /**
     * 等待时长的**防溢出**上限（约 3 年）。
     *
     * ★ v1.24.9：这里刻意不是"业务范围"。用户明确要求
     *   「不要范围设定在 5～3600 秒，秒 / 分 / 时可以自由选择输入」，
     *   所以 UI 一律不 clamp；数据层只保留这一个防止 `Int` 溢出的天花板，
     *   与 [com.activitylock.app.ui.components.MAX_DURATION_SEC] 同口径。
     */
    const val MAX_DELAY_SEC = 100_000_000

    /** 把任意输入收敛到可安全存储的等待秒数（下限 1 秒：0 秒等于没设等待）。 */
    fun safeDelaySec(sec: Int): Int = sec.coerceIn(1, MAX_DELAY_SEC)

    /**
     * 等待倒计时的文案。
     *
     * ★ 为什么需要它：等待时长放开到「任意数字」后，原来那句 `$remaining 秒后可解锁`
     *   会变成 `7200 秒后可解锁` —— 数字本身已经失去可读性。
     *   按量级切换：不足 1 分钟 → `45 秒`；不足 1 小时 → `12 分 30 秒`；否则 → `2 小时 0 分`。
     */
    fun delayCountdownLabel(sec: Int): String = when {
        sec < 60 -> "$sec 秒"
        sec < 3600 -> "${sec / 60} 分 ${sec % 60} 秒"
        else -> "${sec / 3600} 小时 ${(sec % 3600) / 60} 分"
    }

    fun password(ctx: Context): String = prefs(ctx).getString("password", "") ?: ""

    fun save(ctx: Context, enabled: Boolean, method: String, password: String, delaySec: Int, quizCount: Int = 1) {
        prefs(ctx).edit()
            .putBoolean("enabled", enabled)
            .putString("method", method)
            .putString("password", password)
            // ★ v1.24.9：只做防溢出，不再钉死 5–3600（用户要求自由输入）
            .putInt("delay_sec", safeDelaySec(delaySec))
            .putInt("quiz_count", quizCount.coerceIn(1, MAX_COUNT))
            .apply()
    }

    /**
     * 题数 / 位数的**防溢出上限**（不是业务限制）。
     *
     * ★ v1.29.0：原来全局验证写死 `quiz_count 1..10`、`random_digits 4..12`，
     *   而界面级解锁条件（[RuleStore]）早就放到 1..999 —— **同一个概念两套上限**，
     *   于是「界面级能设 50 题、全局只能 10 题」。现在统一到这个上限，
     *   与「时长不做范围限制、只防溢出」同一条规矩。
     */
    const val MAX_COUNT = 999

    /** 全局答题数量（1 – [MAX_COUNT] 题）。 */
    fun quizCount(ctx: Context): Int = prefs(ctx).getInt("quiz_count", 1).coerceIn(1, MAX_COUNT)

    fun setQuizCount(ctx: Context, count: Int) {
        prefs(ctx).edit().putInt("quiz_count", count.coerceIn(1, MAX_COUNT)).apply()
    }

    /** 随机密码位数（1 – [MAX_COUNT] 位），默认 6。 */
    fun randomDigits(ctx: Context): Int = prefs(ctx).getInt("random_digits", 6).coerceIn(1, MAX_COUNT)

    fun setRandomDigits(ctx: Context, digits: Int) {
        prefs(ctx).edit().putInt("random_digits", digits.coerceIn(1, MAX_COUNT)).apply()
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
                    // 选了「密码」却没填密码 → 退回等待（用全局设置的秒数）
                    Requirement(METHOD_DELAY, safeDelaySec(delaySeconds(ctx)), emptyList())
                method(ctx) == METHOD_QUIZ ->
                    Requirement(METHOD_QUIZ, 0, emptyList(), quizCount = quizCount(ctx))
                method(ctx) == METHOD_RANDOM ->
                    Requirement(METHOD_RANDOM, 0, emptyList(), randomDigits = randomDigits(ctx))
                else ->
                    // ★ v1.24.9：不再 coerceIn(5, 3600) —— 用户可自由填秒/分/小时
                    Requirement(method(ctx), safeDelaySec(delaySeconds(ctx)), listOf(pwd))
            }
        } else Requirement(MODE_NONE, 0, emptyList())

    /**
     * 某条规则的有效解锁条件，**三级优先级**解析：
     *
     *   界面级（[LockRule.verifyMode]） > 应用级（[AppVerify]） > 全局
     *
     * 每一层「未设置（null）」就下探一层；命中哪一层就用哪一层的参数
     * （该层参数缺失时按该层规则回退，例如选了「密码」但没填密码 → 退回等待）。
     *
     * ★ 是**择一**而不是叠加：界面级显式设了「等待 5 秒」，就不会再被全局的
     *   「密码」盖住。旧实现在批量场景里无条件叠加全局条件，导致
     *   「明明给这个界面单独设了等待，解锁却还要输密码」——见 [bulkRequirement]。
     */
    fun requirementFor(ctx: Context, rule: LockRule?): Requirement {
        if (rule != null && rule.verifyMode != null) {
            return buildFrom(ctx, rule.verifyMode, rule.verifyDelaySec, rule.verifyPassword, rule.quizCount)
        }
        val av = rule?.packageName?.let { appVerifyProvider?.invoke(it) }
        if (av != null && av.verifyMode != null) {
            return buildFrom(ctx, av.verifyMode, av.verifyDelaySec, av.verifyPassword, av.quizCount)
        }
        return globalRequirement(ctx)
    }

    /**
     * 把某一层的 (mode + 参数) 解析为 [Requirement]。
     *
     * 注：`METHOD_RANDOM` 的位数、`METHOD_QUIZ` 的题数若该层没填，回退到全局设置。
     */
    private fun buildFrom(
        ctx: Context,
        mode: String?,
        delaySec: Int?,
        pwd: String?,
        quiz: Int?,
    ): Requirement = when (mode) {
        MODE_NONE -> Requirement(MODE_NONE, 0, emptyList())
        METHOD_QUIZ -> Requirement(METHOD_QUIZ, 0, emptyList(), quizCount = quiz ?: quizCount(ctx))
        METHOD_RANDOM -> Requirement(METHOD_RANDOM, 0, emptyList(), randomDigits = randomDigits(ctx))
        METHOD_DELAY, METHOD_PASSWORD -> {
            // ★ v1.24.9：只防溢出，不再钉死 5–3600（界面/应用/全局三层同口径）
            val d = safeDelaySec(delaySec ?: delaySeconds(ctx))
            val p = pwd?.takeIf { it.isNotBlank() } ?: password(ctx)
            if (mode == METHOD_PASSWORD && p.isBlank()) Requirement(METHOD_DELAY, d, emptyList())
            else Requirement(mode, d, listOf(p))
        }
        // 未知 / null → 回到全局（防御旧数据里出现非法 mode）
        else -> globalRequirement(ctx)
    }

    /**
     * 某个应用**应用级**的有效解锁条件（不含界面级覆盖）：
     * 应用级设置了就用应用级，否则跟随全局。
     *
     * 供「应用级解锁条件」编辑框回填 / 比对使用。
     */
    fun appLevelRequirement(ctx: Context, pkg: String): Requirement {
        val av = appVerifyProvider?.invoke(pkg)
        if (av != null && av.verifyMode != null) {
            return buildFrom(ctx, av.verifyMode, av.verifyDelaySec, av.verifyPassword, av.quizCount)
        }
        return globalRequirement(ctx)
    }

    /**
     * 批量操作（磁贴「全部解锁」、场景解锁、本页关开关的验证）的条件：
     * 取所有**相关界面各自生效条件**中的最强 ——
     * 随机密码 > 密码 > 答题 > 等待；密码可接受其中任意一个，等待取最长。
     *
     * ★ 修复点（v1.15.0）：不再**无条件叠加** [globalRequirement]。
     *   旧实现是 `add(globalRequirement(ctx))` + 各规则，于是全局条件等于
     *   "始终压在所有界面之上"：用户给某界面单独设了「等待 5 秒」，
     *   批量解锁依旧要求全局的「输入密码」，与"界面级优先"自相矛盾，
     *   也与 [requirementFor]（单界面解锁走的那条路）的口径不一致。
     *   现在与 [requirementFor] 完全同口径：每个界面只看它自己的生效条件。
     *   仅当没有任何已锁界面时才回退到全局条件（否则就成了"无需验证"）。
     */
    fun bulkRequirement(ctx: Context, rules: List<LockRule>): Requirement {
        val locked = rules.filter { it.locked }
        if (locked.isEmpty()) return globalRequirement(ctx)
        val reqs = locked.map { requirementFor(ctx, it) }
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

    /**
     * 某个应用下所有**已锁**界面的最强条件 = 「想解锁这个 App 里的任意界面，
     * 至少要通过哪一关」。
     *
     * 自动锁闭页用它把结果直接标在应用卡片上（"本应用按最严执行：输入密码"），
     * 让用户一眼看到实际门槛，而不是对着几条不同的界面条件猜。
     */
    fun bulkRequirementForApp(ctx: Context, rules: List<LockRule>, pkg: String): Requirement =
        bulkRequirement(ctx, rules.filter { it.packageName == pkg })

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
