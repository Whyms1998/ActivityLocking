package com.activitylock.app.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 特赦通行证 —— v1.29.0 新增。
 *
 * ## 它解决的是哪一个问题
 *
 * 之前的矛盾是「解锁验证越强越不敢用，越弱越拦不住」：
 * 验证只有一次机会，冲动只要赢一次就**永久解锁**，所以强度必须一直往上加，
 * 加到应急时自己也进不去，于是又不得不留后门 —— 天平永远调不平。
 *
 * 特赦把「日常通行」和「紧急破例」拆成两条完全独立的路径：
 *  - **日常**：改配置、关开关、移名单 —— 验证可以做到极难，因为不需要它救急；
 *  - **特赦**：真的要用一下 —— 单独一套**较轻**的准入，但**次数极少、越用越难、越用越短**。
 *
 * 关键不在"验证有多难"，而在**稀缺**。一周只有 2 次，用掉了就是没了，
 * 门槛轻一点反而更容易抵住冲动（用户不会为了省事去按一个"只剩 1 次"的按钮）。
 *
 * ## 三个杠杆（全部可自定义）
 *
 * 1. **配额**：周期（日 / 周）+ 次数上限，用完即止。
 * 2. **准入递增**（[escalate]）：本周期第 n 次 = 基础条件 × n
 *    —— 等待时间翻倍、答题数 +2。第 1 次最容易，越往后越烦躁。
 * 3. **时长递减**（[shrink]）：单次放行 = 基础时长 / n
 *    —— 第一次给满，第二次减半，第三次只有三分之一。不改任何交互就能形成威慑。
 *
 * ## 与旧「临时解锁」的关系
 *
 * `LockStrength` 的 `temp_unlock_*` 是旧口径（全局开关 + 每日次数）。
 * 本类**不复用**它：两套计数并存必然出现"用磁贴绕过配额"的漏洞。
 * 磁贴与设置页都改为读本类，旧口径只保留读取兼容，不再作为判决依据。
 */
object AmnestyStore {

    private const val NAME = "amnesty"

    /** 周期口径。 */
    const val PERIOD_DAY = "day"
    const val PERIOD_WEEK = "week"

    /** 单次放行默认 10 分钟 —— 足够查完东西、办完事，又不够刷一轮。 */
    const val DEFAULT_DURATION_SEC = 600

    /** 默认每周 2 次：紧急事一周不会天天有，给多了就变日常。 */
    const val DEFAULT_LIMIT = 2

    /** 递减下限：再少也要够看一眼，低于 60 秒等于没给。 */
    const val MIN_DURATION_SEC = 60

    /** 递减/递增计算用的防溢出上限（约 3 年），不是业务限制。 */
    const val MAX_SEC = 100_000_000

    /** 题数 / 位数 / 次数上限的防溢出上限，与 [VerifyGate.MAX_COUNT] 同口径。 */
    const val MAX_COUNT = VerifyGate.MAX_COUNT

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ---------------- 总开关 ----------------

    /** 默认关：这是最后一道破例口，必须由用户自己打开，不能默认留门。 */
    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    fun setEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", v).apply()
    }

    // ---------------- 配额 ----------------

    fun durationSec(ctx: Context): Int =
        prefs(ctx).getInt("duration_sec", DEFAULT_DURATION_SEC).coerceIn(1, MAX_SEC)

    fun setDurationSec(ctx: Context, sec: Int) {
        // ★ 与全 App 输入口径一致：落盘不取整、不做业务范围限制，只防溢出。
        prefs(ctx).edit().putInt("duration_sec", sec.coerceIn(1, MAX_SEC)).apply()
    }

    fun period(ctx: Context): String =
        prefs(ctx).getString("period", PERIOD_WEEK) ?: PERIOD_WEEK

    fun setPeriod(ctx: Context, p: String) {
        prefs(ctx).edit().putString("period", if (p == PERIOD_DAY) PERIOD_DAY else PERIOD_WEEK).apply()
    }

    /** 本周期可用的总次数。`0` = 禁用。上限 999 仅为防溢出，不是业务限制。 */
    fun limit(ctx: Context): Int = prefs(ctx).getInt("limit", DEFAULT_LIMIT).coerceIn(0, MAX_COUNT)

    fun setLimit(ctx: Context, n: Int) {
        prefs(ctx).edit().putInt("limit", n.coerceIn(0, MAX_COUNT)).apply()
    }

    // ---------------- 准入条件（与日常解锁完全独立） ----------------

    /** 准入方式：none / delay / quiz / random / password。默认等待，最容易。 */
    fun verifyMode(ctx: Context): String =
        prefs(ctx).getString("verify_mode", VerifyGate.METHOD_DELAY) ?: VerifyGate.METHOD_DELAY

    fun setVerifyMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString("verify_mode", mode).apply()
    }

    fun verifyDelaySec(ctx: Context): Int =
        prefs(ctx).getInt("verify_delay_sec", 30).coerceIn(1, MAX_SEC)

    fun setVerifyDelaySec(ctx: Context, sec: Int) {
        prefs(ctx).edit().putInt("verify_delay_sec", sec.coerceIn(1, MAX_SEC)).apply()
    }

    fun verifyQuizCount(ctx: Context): Int =
        prefs(ctx).getInt("verify_quiz_count", 2).coerceIn(1, VerifyGate.MAX_COUNT)

    fun setVerifyQuizCount(ctx: Context, n: Int) {
        prefs(ctx).edit().putInt("verify_quiz_count", n.coerceIn(1, VerifyGate.MAX_COUNT)).apply()
    }

    fun verifyRandomDigits(ctx: Context): Int =
        prefs(ctx).getInt("verify_random_digits", 6).coerceIn(1, VerifyGate.MAX_COUNT)

    fun setVerifyRandomDigits(ctx: Context, n: Int) {
        prefs(ctx).edit().putInt("verify_random_digits", n.coerceIn(1, VerifyGate.MAX_COUNT)).apply()
    }

    fun verifyPassword(ctx: Context): String =
        prefs(ctx).getString("verify_password", "") ?: ""

    fun setVerifyPassword(ctx: Context, p: String) {
        prefs(ctx).edit().putString("verify_password", p).apply()
    }

    // ---------------- 递增 ----------------

    /** 准入递增：本周期第 n 次按 n 倍加难（等待翻倍 / 答题 +2）。 */
    fun escalate(ctx: Context): Boolean = prefs(ctx).getBoolean("escalate", true)

    fun setEscalate(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("escalate", v).apply()
    }

    /** 时长递减：单次放行 = 基础时长 / n。 */
    fun shrink(ctx: Context): Boolean = prefs(ctx).getBoolean("shrink", true)

    fun setShrink(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("shrink", v).apply()
    }

    // ---------------- 计数 ----------------

    /**
     * 当前周期的 key。
     *
     * ★ 必须显式 `Locale.US`：不指定时，泰历 / 阿拉伯语区域会格式化成非公历年份，
     *   与 [LockStrength] / [QuotaState] 的口径不一致，跨模块比对就出错（v1.24.13 的教训）。
     */
    fun periodKey(ctx: Context, now: Long = System.currentTimeMillis()): String {
        if (period(ctx) == PERIOD_WEEK) {
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            return String.format(
                Locale.US, "%04d-W%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.WEEK_OF_YEAR),
            )
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(now))
    }

    /** 本周期已用次数（跨周期自动归零）。 */
    fun used(ctx: Context): Int {
        val p = prefs(ctx)
        if (p.getString("used_key", "") != periodKey(ctx)) return 0
        return p.getInt("used", 0)
    }

    @Synchronized
    fun record(ctx: Context) {
        val p = prefs(ctx)
        val key = periodKey(ctx)
        val used = if (p.getString("used_key", "") != key) 0 else p.getInt("used", 0)
        p.edit()
            .putString("used_key", key)
            .putInt("used", used + 1)
            .putLong("last_used_at", System.currentTimeMillis())
            .putInt("last_duration_sec", durationForNext(ctx))
            .apply()
    }

    fun lastUsedAt(ctx: Context): Long = prefs(ctx).getLong("last_used_at", 0L)

    fun remaining(ctx: Context): Int = (limit(ctx) - used(ctx)).coerceAtLeast(0)

    /** 现在能不能申请：总开关开 + 还有次数。 */
    fun allowed(ctx: Context): Boolean = enabled(ctx) && remaining(ctx) > 0

    // ---------------- 本次生效值 ----------------

    /**
     * **这一次**能放行多久。
     *
     * 第 1 次 = 基础时长，第 2 次 = 一半，第 3 次 = 三分之一……
     * 下限 [MIN_DURATION_SEC]，避免出现"批了 12 秒"这种荒唐结果。
     */
    fun durationForNext(ctx: Context): Int {
        val base = durationSec(ctx)
        if (!shrink(ctx)) return base
        val k = used(ctx) + 1
        return (base / k).coerceAtLeast(MIN_DURATION_SEC)
    }

    /**
     * **这一次**要过哪一关。
     *
     * 与日常解锁的 `VerifyGate` 完全无关 —— 这里只读特赦自己的配置。
     * 递增规则：等待 × n、答题 +2×n；密码与照抄不递增（它们本身就是一次性动作，
     * 加长只会让人放弃记录，反而催生"把密码写在纸上"这种更糟的应对）。
     */
    fun requirementForNext(ctx: Context): VerifyGate.Requirement {
        val n = used(ctx)
        val k = if (escalate(ctx)) n + 1 else 1
        // ★ 等待是特赦的「地板」：任何非法/空配置都退回到它，**绝不返回 none**。
        //   旧实现有两个零门槛出口：password 分支在密码为空时返回空密码
        //   （passwordOk 永远 false → 弹窗里等于无门槛），else 分支直接返回 none。
        //   「特赦可以没有门槛」本身就是这个功能最大的漏洞。
        val delay = VerifyGate.Requirement(
            VerifyGate.METHOD_DELAY,
            (verifyDelaySec(ctx).toLong() * k).coerceAtMost(MAX_SEC.toLong()).toInt(),
            emptyList(),
        )
        return when (verifyMode(ctx)) {
            VerifyGate.METHOD_QUIZ -> VerifyGate.Requirement(
                VerifyGate.METHOD_QUIZ, 0, emptyList(),
                quizCount = (verifyQuizCount(ctx) + 2L * n)
                    .coerceAtMost(VerifyGate.MAX_COUNT.toLong()).toInt()
                    .coerceAtLeast(1),
            )
            VerifyGate.METHOD_RANDOM -> VerifyGate.Requirement(
                VerifyGate.METHOD_RANDOM, 0, emptyList(),
                randomDigits = verifyRandomDigits(ctx),
            )
            VerifyGate.METHOD_PASSWORD -> {
                val pwd = verifyPassword(ctx)
                if (pwd.isBlank()) delay
                else VerifyGate.Requirement(VerifyGate.METHOD_PASSWORD, 0, listOf(pwd))
            }
            else -> delay
        }
    }

    /**
     * 改特赦参数时该过哪一关。
     *
     * ★ v1.29.1：原来只过「日常解锁验证」—— 而日常验证可以被关掉、
     *   也可以被设成很轻，于是「把特赦从密码降成等待」不需要付任何代价，
     *   等于特赦的强度可以随手往下调。
     *   现在取 **日常验证与当前特赦准入中更严的那一档**：
     *   想降低门，先过降低前的那道门。
     *
     * 注意这仍然不是"不可撤销"：用户自己把关掉日常验证也是他的自由，
     * 本函数只保证「设定的门槛不会低于正在生效的门槛」。
     */
    fun editRequirement(ctx: Context): VerifyGate.Requirement {
        val daily = VerifyGate.globalRequirement(ctx)
        val amnesty = requirementForNext(ctx)
        return if (strength(daily) >= strength(amnesty)) daily else amnesty
    }

    /** 只用于比较两档门槛谁更严，不是业务值。 */
    private fun strength(r: VerifyGate.Requirement): Long = when (r.mode) {
        VerifyGate.METHOD_DELAY -> 1_000_000L + r.delaySec.toLong()
        VerifyGate.METHOD_QUIZ -> 2_000_000L + r.quizCount.toLong()
        VerifyGate.METHOD_RANDOM -> 3_000_000L + r.randomDigits.toLong()
        // 空密码的「密码」档等于没有门槛，不能算最严
        VerifyGate.METHOD_PASSWORD -> if (r.passwords.any { it.isBlank() }) 0L else 4_000_000L
        else -> 0L
    }

    /** 本次准入的一句话说人话，给页面直接显示。 */
    fun verifySummary(ctx: Context): String {
        val n = used(ctx)
        val req = requirementForNext(ctx)
        val base = when (req.mode) {
            VerifyGate.METHOD_DELAY -> "等待 " + com.activitylock.app.ui.screens.relockDelayLabel(req.delaySec)
            VerifyGate.METHOD_QUIZ -> "连对 ${req.quizCount} 道口算"
            VerifyGate.METHOD_RANDOM -> "照抄 ${req.randomDigits} 位随机密码"
            VerifyGate.METHOD_PASSWORD -> "输入特赦密码"
            else -> "无需验证"
        }
        return if (escalate(ctx) && n > 0) "$base，本周期第 ${n + 1} 次" else base
    }
}
