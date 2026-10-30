package com.activitylock.app.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 核心决策逻辑的 JVM 单元测试（v1.34.0 收官新增）。
 *
 * 范围：**不依赖 Android Context 的纯函数**——它们是历史上「假开关」类 bug 的高发区：
 *  - [superviseWeakened]：削弱判定（v1.33.0 防规避闸门的判据，判错 = 额度可被静默调大）
 *  - [VerifyGate.strength]：验证方式强度排序（批量取最严的基础，排错 = 批量解锁被降档）
 *  - [SuperviseTask] JSON 序列化往返（schema 变更防线）
 *  - [cappedUsed]：超额读数封顶
 *
 * lockSec / bulkRequirement / durationForNext 强依赖 Context 与 SharedPreferences，
 * JVM 无法直接实例化，留待 Robolectric 或重构注入后补充。
 */
class CoreDecisionTest {

    // ---- superviseWeakened：放宽必须拦住，收紧必须放行 ----

    private fun task(
        name: String = "t",
        enabled: Boolean = true,
        forbid: Boolean = false,
        daySec: Int? = null,
        dayCnt: Int? = null,
        winSec: Int? = null,
        winCnt: Int? = null,
        windowStart: Int = 0,
        windowEnd: Int = 1439,
        targets: List<SuperviseTarget> = listOf(SuperviseTarget("com.a")),
    ) = SuperviseTask(
        name = name, enabled = enabled,
        windowStart = windowStart, windowEnd = windowEnd,
        targets = targets,
        rules = SuperviseRules(
            forbid = forbid, windowLimitSec = winSec, windowLimitCnt = winCnt,
            dayLimitSec = daySec, dayLimitCnt = dayCnt,
        ),
    )

    @Test
    fun `null old never weakened`() {
        assertFalse(superviseWeakened(null, task(daySec = 3600)))
    }

    @Test
    fun `disable task is weakened`() {
        assertTrue(superviseWeakened(task(daySec = 600), task(daySec = 600, enabled = false)))
    }

    @Test
    fun `disabled task being edited is never weakened`() {
        // 旧任务本来就关着：怎么改都不算放宽（开关本身就是闸）
        assertFalse(superviseWeakened(task(daySec = 600, enabled = false), task()))
    }

    @Test
    fun `turning off forbid is weakened`() {
        assertTrue(superviseWeakened(task(forbid = true), task(forbid = false)))
    }

    @Test
    fun `larger limits are weakened`() {
        assertTrue(superviseWeakened(task(daySec = 600), task(daySec = 3600)))
        assertTrue(superviseWeakened(task(dayCnt = 5), task(dayCnt = 50)))
        assertTrue(superviseWeakened(task(winSec = 300), task(winSec = 3000)))
        assertTrue(superviseWeakened(task(winCnt = 3), task(winCnt = null)))
    }

    @Test
    fun `clearing limits is weakened`() {
        assertTrue(superviseWeakened(task(daySec = 600), task()))
    }

    @Test
    fun `tighter limits are not weakened`() {
        assertFalse(superviseWeakened(task(daySec = 3600, dayCnt = 20), task(daySec = 600, dayCnt = 10)))
    }

    @Test
    fun `adding limits is not weakened`() {
        assertFalse(superviseWeakened(task(), task(daySec = 600, dayCnt = 5)))
    }

    @Test
    fun `removing target is weakened`() {
        val two = task(targets = listOf(SuperviseTarget("com.a"), SuperviseTarget("com.b")))
        val one = task(targets = listOf(SuperviseTarget("com.a")))
        assertTrue(superviseWeakened(two, one))
        assertFalse(superviseWeakened(one, two))
    }

    @Test
    fun `shrinking window is weakened`() {
        // 8:00-23:00 缩到 22:00-23:00：白天一段没人管
        assertTrue(superviseWeakened(
            task(daySec = 600, windowStart = 480, windowEnd = 1380),
            task(daySec = 600, windowStart = 1320, windowEnd = 1380),
        ))
    }

    @Test
    fun `widening window is not weakened`() {
        assertFalse(superviseWeakened(
            task(daySec = 600, windowStart = 480, windowEnd = 1380),
            task(daySec = 600, windowStart = 0, windowEnd = 1439),
        ))
    }

    @Test
    fun `crossing midnight window shrink detected`() {
        // 跨夜 23:00-07:00 (start=1380 > end=420) 缩到 23:00-24:00
        assertTrue(superviseWeakened(
            task(daySec = 600, windowStart = 1380, windowEnd = 420),
            task(daySec = 600, windowStart = 1380, windowEnd = 1439),
        ))
    }

    // ---- VerifyGate.strength：随机码 > 密码 > 答题 > 等待 > 无 ----

    @Test
    fun `strength ordering is strict`() {
        assertTrue(VerifyGate.strength(VerifyGate.METHOD_RANDOM) > VerifyGate.strength(VerifyGate.METHOD_PASSWORD))
        assertTrue(VerifyGate.strength(VerifyGate.METHOD_PASSWORD) > VerifyGate.strength(VerifyGate.METHOD_QUIZ))
        assertTrue(VerifyGate.strength(VerifyGate.METHOD_QUIZ) > VerifyGate.strength(VerifyGate.METHOD_DELAY))
        assertTrue(VerifyGate.strength(VerifyGate.METHOD_DELAY) > VerifyGate.strength("none"))
        assertEquals(0, VerifyGate.strength("unknown-mode"))
    }

    // ---- SuperviseTask JSON 往返：schema 防线 ----

    @Test
    fun `supervise task json roundtrip`() {
        val t = task(
            name = "微信监督", forbid = true, daySec = 1800, dayCnt = 20,
            windowStart = 480, windowEnd = 1380,
            targets = listOf(SuperviseTarget("com.tencent.mm"), SuperviseTarget("com.tencent.mm", ".plugin.sns.SnsTimeLineUI")),
        )
        val json = Json { ignoreUnknownKeys = true }
        val s = json.encodeToString(SuperviseTask.serializer(), t)
        val back = json.decodeFromString(SuperviseTask.serializer(), s)
        assertEquals(t, back)
        assertTrue(back.rules.forbid)
        assertEquals(1800, back.rules.dayLimitSec)
        assertEquals(20, back.rules.dayLimitCnt)
        assertEquals(2, back.targets.size)
        assertEquals("com.tencent.mm/.plugin.sns.SnsTimeLineUI", back.targets[1].key)
    }

    @Test
    fun `json with unknown keys still parses`() {
        // 旧版本多余字段不致命：ignoreUnknownKeys 兜底
        val s = """{"name":"旧任务","futureField":123,"rules":{"dayLimitSec":600,"notYetKnown":1},"targets":[{"pkg":"com.a"}]}"""
        val back = Json { ignoreUnknownKeys = true }.decodeFromString(SuperviseTask.serializer(), s)
        assertEquals("旧任务", back.name)
        assertEquals(600, back.rules.dayLimitSec)
    }

    // ---- cappedUsed：超额读数封顶 ----

    @Test
    fun `used capped at limit`() {
        assertEquals(20, cappedUsed(98, 20))
        assertEquals(8, cappedUsed(8, 20))
        assertEquals(98, cappedUsed(98, 0)) // 无限制不封顶
    }
}
