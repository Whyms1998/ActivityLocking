package com.activitylock.app.core

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
// ★ v1.24.9：时长读数全 App 只此一份（internal，同模块可用）——
//   磁贴副标题与设置页「当前 …」、临时解锁页文案必须是同一个口径。
import com.activitylock.app.ui.screens.relockDelayLabel

/**
 * 「临时解锁」控制中心磁贴：钥匙图标。
 * 点击 → 全屏验证（所有锁定界面的最强条件）→ 全部临时解锁 LockStrength 时长 → 到期自动落锁。
 * 豁免期内磁贴为激活态，再点 = 立即全部落锁。
 */
class TempUnlockTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        super.onClick()
        AppStore.ensureInit(applicationContext)
        val until = UnlockExemptions.until(applicationContext)

        if (until > System.currentTimeMillis()) {
            // 豁免中：再点 = 立即全部落锁
            UnlockExemptions.removeGlobal(applicationContext)
            AmsController.writeRules()
            // ★ TileService.onClick 跑在主线程，而下面要对每个锁定包各跑一次
            //   Shizuku force-stop（每包一次 IPC + 子进程启动），锁定包多时
            //   会阻塞数秒 → ANR。挪到后台线程，完成后回主线程刷新磁贴。
            Thread {
                val pkgs = AppStore.rules.filter { it.locked }.map { it.packageName }.distinct()
                for (p in pkgs) runCatching { ShizukuManager.run("am", "force-stop", p) }
                android.os.Handler(android.os.Looper.getMainLooper()).post { refresh() }
            }.start()
            return
        }
        if (AppStore.rules.none { it.locked }) {
            refresh()
            return
        }
        // ★ v1.24.9：计划窗口闸门 —— 与 TempUnlockActivity / AmsController 同一判据。
        //   「计划窗口内禁用临时解锁」开着时，放行验证=假动作（Watcher 不放行），直接拦。
        if (LockStrength.noExemptInSchedule(applicationContext) &&
            ScheduleEngine.anyActive(AppStore.schedules)
        ) {
            refresh()
            return
        }
        // ★ v1.24.9：次数闸门 —— 与 TempUnlockActivity 同一判据，上限用完 / 被禁用时不放行
        if (!LockStrength.tempUnlockAllowed(applicationContext)) {
            refresh()
            return
        }
        // 拉起全屏验证页（全局临时解锁）
        val i = android.content.Intent(this, com.activitylock.app.ui.TempUnlockActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = android.app.PendingIntent.getActivity(
            this, 2001, i,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        // ★ startActivityAndCollapse(PendingIntent) 重载是 API 34 才有的；
        //   minSdk=26，低版本必须回退到旧的 Intent 重载，否则 NoSuchMethodError 崩溃。
        //   （与 BaseLockTileService 的处理保持一致）
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        AppStore.ensureInit(applicationContext)
        val until = UnlockExemptions.until(applicationContext)
        val lockedCount = AppStore.rules.count { it.locked }
        // ★ v1.24.9：次数上限同步到磁贴 —— 设了上限却照样能点 = 假开关
        val dailyMax = LockStrength.tempUnlockDailyMax(applicationContext)
        val usedToday = LockStrength.tempUnlockUsedToday(applicationContext)
        // ★ v1.24.9：计划窗口闸门同样同步（否则磁贴可点、点完不生效 = 假动作）。
        //   判定放在豁免态之前：计划生效期间豁免被抑制，磁贴不应再显示「剩余 N 分」。
        val planBlocked = LockStrength.noExemptInSchedule(applicationContext) &&
            ScheduleEngine.anyActive(AppStore.schedules)
        when {
            lockedCount == 0 -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.setSubtitleCompat("没有锁定中的界面")
            }
            planBlocked -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.setSubtitleCompat("计划生效中，临时解锁已禁用")
            }
            dailyMax == 0 -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.setSubtitleCompat("临时解锁已禁用")
            }
            dailyMax > 0 && usedToday >= dailyMax -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.setSubtitleCompat("今日次数已用完")
            }
            until > System.currentTimeMillis() -> {
                tile.state = Tile.STATE_ACTIVE
                val remainMin = ((until - System.currentTimeMillis()) / 60_000).toInt() + 1
                // ★ 规范 §1：原来「临时解锁中 · 剩余 N 分（点按落锁）」太挤，
                //   而且磁贴 state=ACTIVE 已经表达了"临时解锁中" → 副标题只留余量 + 操作
                tile.setSubtitleCompat("剩余 ${remainMin} 分（点按上锁）")
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                // ★ v1.24.9：原来用 tempUnlockMin()（整数除法），90 秒会读成「1 分钟」，
                //   与实际放行时长不符 —— 统一走秒级读数（与设置页同一函数）。
                tile.setSubtitleCompat(
                    "全部临时解锁 " + relockDelayLabel(LockStrength.tempUnlockSec(applicationContext))
                )
            }
        }
        tile.updateTile()
    }
}
