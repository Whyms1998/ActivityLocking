package com.activitylock.app.core

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.activitylock.app.AmnestyApplyActivity
// ★ 时长读数全 App 只此一份（磁贴副标题与设置页必须同一口径）。
import com.activitylock.app.ui.screens.relockDelayLabel

/**
 * 「特赦」控制中心磁贴：钥匙图标。
 *
 * ★ v1.29.0：语义从「临时解锁」改为【特赦】的快捷入口。
 * ★ v1.29.1：点击**直接进验证**（[AmnestyApplyActivity]），验完即放行并关闭 ——
 *   不再先进设置页。磁贴不自己跑验证，验证逻辑仍然只有一份（否则两套配额可绕过）。
 *
 * 保留一个很实用的例外：**豁免进行中再点 = 立即全部落锁**。
 * 这比进 App 找"全部上锁"快得多，也是临时放行后最常想做的动作。
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

        // 其余情况：直接进验证。
        // ★ v1.29.1：原先是打开特赦设置页 —— 点磁贴的人只想"现在用一下"，
        //   却要先看一遍配额/准入/递增，再自己点「申请」，而那一页恰好也是
        //   「干脆把次数调高一点」的入口。现在能申请就直接给验证卡片，
        //   屏幕上没有任何配置项；不能申请时才转去特赦页说明原因。
        val i = android.content.Intent(this, AmnestyApplyActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = android.app.PendingIntent.getActivity(
            this, 2001, i,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        // ★ startActivityAndCollapse(PendingIntent) 重载是 API 34 才有的；
        //   minSdk=26，低版本必须回退到旧的 Intent 重载，否则 NoSuchMethodError 崩溃。
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

        val enabled = AmnestyStore.enabled(applicationContext)
        val remaining = AmnestyStore.remaining(applicationContext)
        val planBlocked = LockStrength.noExemptInSchedule(applicationContext) &&
            ScheduleEngine.anyActive(AppStore.schedules)

        when {
            lockedCount == 0 -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.setSubtitleCompat("没有锁定中的界面")
            }
            until > System.currentTimeMillis() -> {
                tile.state = Tile.STATE_ACTIVE
                val remainMin = ((until - System.currentTimeMillis()) / 60_000).toInt() + 1
                // state=ACTIVE 已经表达了"放行中" → 副标题只留余量 + 操作
                tile.setSubtitleCompat("剩余 ${remainMin} 分（点按上锁）")
            }
            !enabled -> {
                tile.state = Tile.STATE_INACTIVE
                tile.setSubtitleCompat("特赦未启用（点按去开启）")
            }
            planBlocked -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.setSubtitleCompat("计划生效中，不可申请")
            }
            remaining <= 0 -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.setSubtitleCompat("本周期次数已用完")
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                // ★ 副标题只放两个最该知道的数：还剩几次、这次能放多久。
                //   原先是「全部临时解锁 15 分钟」，没有次数 —— 上限形同虚设的观感来源。
                tile.setSubtitleCompat(
                    "剩 $remaining 次 · " + relockDelayLabel(AmnestyStore.durationForNext(applicationContext))
                )
            }
        }
        tile.updateTile()
    }
}
