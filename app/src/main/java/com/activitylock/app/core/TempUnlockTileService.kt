package com.activitylock.app.core

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

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
            val pkgs = AppStore.rules.filter { it.locked }.map { it.packageName }.distinct()
            for (p in pkgs) ShizukuManager.run("am", "force-stop", p)
            refresh()
            return
        }
        if (AppStore.rules.none { it.locked }) {
            refresh()
            return
        }
        // 拉起全屏验证页（全局临时解锁）
        val i = android.content.Intent(this, com.activitylock.app.ui.TempUnlockActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(
            android.app.PendingIntent.getActivity(
                this, 2001, i,
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
        )
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        AppStore.ensureInit(applicationContext)
        val until = UnlockExemptions.until(applicationContext)
        val lockedCount = AppStore.rules.count { it.locked }
        when {
            lockedCount == 0 -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = "没有锁定中的界面"
            }
            until > System.currentTimeMillis() -> {
                tile.state = Tile.STATE_ACTIVE
                val remainMin = ((until - System.currentTimeMillis()) / 60_000).toInt() + 1
                tile.subtitle = "临时解锁中 · 剩余 ${remainMin} 分（点按落锁）"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "全部临时解锁 ${LockStrength.tempUnlockMin(applicationContext)} 分钟"
            }
        }
        tile.updateTile()
    }
}
