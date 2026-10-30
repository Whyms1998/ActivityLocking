package com.activitylock.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份包信封的解析与校验。
 *
 * 这是换机迁移最容易出事的地方：一份坏文件被接受 = 用户以为迁移成功、
 * 实际上规则全丢。所以「拒绝什么」和「接收什么」都要钉住。
 */
class BackupEnvelopeTest {

    private fun good(extraFiles: String = ""): String = """
        {
          "app": "com.activitylock.app",
          "format": 1,
          "versionName": "1.34.1",
          "exportedAt": 1761900000000,
          "files": {
            "rules.json": { "rules": [ {"packageName":"a","activityName":"b"},
                                       {"packageName":"c","activityName":"d"} ],
                            "appVerify": { "a": {} } },
            "supervise_tasks.json": { "tasks": [ {"id":"t1"} ] },
            "groups.json": { "groups": [], "assign": {} }
            $extraFiles
          },
          "prefs": {
            "unlock_verify": { "enabled": {"t":"b","v":true},
                               "method": {"t":"s","v":"password"} }
          }
        }
    """.trimIndent()

    @Test
    fun previewCountsOnlyNonEmptyGroups() {
        val p = BackupStore.parse(good()).preview()
        val map = p.counts.toMap()
        assertEquals(2, map["锁定规则"])
        assertEquals(1, map["监督任务"])
        assertEquals(1, map["应用级解锁"])          // appVerify 非空
        assertEquals(null, map["分组"])             // 空数组不出现在摘要里
        assertEquals(1, p.prefGroups)
        assertEquals("1.34.1", p.versionName)
        assertEquals(4, p.total)                    // 2 + 1 + 1
    }

    @Test
    fun unknownExtraFieldsAreIgnored() {
        // 未来版本多出来的字段不能被当成坏包
        val raw = good().replace("\"format\": 1", "\"format\": 1, \"somethingNew\": {\"x\": 1}")
        val p = BackupStore.parse(raw).preview()
        assertEquals(2, p.counts.toMap()["锁定规则"])
    }

    @Test(expected = BackupStore.ImportException::class)
    fun rejectNonJson() {
        BackupStore.parse("这不是 json")
    }

    @Test(expected = BackupStore.ImportException::class)
    fun rejectForeignApp() {
        BackupStore.parse(good().replace("com.activitylock.app", "com.other.app"))
    }

    @Test(expected = BackupStore.ImportException::class)
    fun rejectNewerFormat() {
        BackupStore.parse(good().replace("\"format\": 1", "\"format\": 9"))
    }

    @Test(expected = BackupStore.ImportException::class)
    fun rejectEmptyBundle() {
        BackupStore.parse(
            """{"app":"com.activitylock.app","format":1,"files":{},"prefs":{}}"""
        )
    }

    @Test
    fun acceptBundleWithOnlyPrefs() {
        // 只有设置项（例如用户只配了验证密码，没有任何规则）也是合法备份
        val raw = """{"app":"com.activitylock.app","format":1,"prefs":{"self_code":{"hash":{"t":"s","v":"x"}}}}"""
        val p = BackupStore.parse(raw).preview()
        assertTrue(p.counts.isEmpty())
        assertEquals(1, p.prefGroups)
    }

    /**
     * 「今日进度」必须被挡住。
     *
     * 这条钉的是防绕过：`amnesty.used` / `tu_used_today` 若随导入清零，
     * 用户导出一次再导入一次，就把今天用掉的特赦与临时解锁次数洗回来了。
     */
    @Test
    fun progressCountersAreNotMigrated() {
        assertTrue(BackupStore.isProgressKey("amnesty", "used"))
        assertTrue(BackupStore.isProgressKey("amnesty", "used_key"))
        assertTrue(BackupStore.isProgressKey("lock_strength", "tu_used_today"))
        assertTrue(BackupStore.isProgressKey("lock_strength", "tu_date"))
        assertTrue(BackupStore.isProgressKey("schedule_state", "state"))
        // 配置本身必须照常迁移
        assertFalse(BackupStore.isProgressKey("amnesty", "duration_sec"))
        assertFalse(BackupStore.isProgressKey("amnesty", "limit"))
        assertFalse(BackupStore.isProgressKey("lock_strength", "temp_unlock_sec"))
        assertFalse(BackupStore.isProgressKey("unlock_verify", "password"))
        assertFalse(BackupStore.isProgressKey("unknown_prefs", "x"))
    }
}
