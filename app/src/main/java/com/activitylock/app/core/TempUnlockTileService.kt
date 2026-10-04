package com.activitylock.app.core

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * 「临时解锁」控制中心磁贴：钥匙图标。
 * 点击 → 定位最近被拦截的 App → 全屏验证 → 豁免 LockStrength 时长并自动打开该 App。
 * 豁免期内磁贴为激活态，再点 = 立即恢复拦截。
 */
class TempUnlockTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        super.onClick()
        AppStore.ensureInit(applicationContext)
        val last = BlockStats.lastBlockedPkg()
        val activeUntil = UnlockExemptions.active(applicationContext)[last]

        if (last != null && activeUntil != null && activeUntil > System.currentTimeMillis()) {
            // 豁免中：再点 = 立即恢复拦截
            UnlockExemptions.remove(applicationContext, last)
            AmsController.writeRules()
            ShizukuManager.run("am", "force-stop", last)
            refresh()
            return
        }
        if (last == null) {
            refresh()
            return
        }
        // 拉起验证页（验证通过后豁免 + 自动打开该 App）
        startActivityAndCollapse(
            android.content.Intent(this, com.activitylock.app.ui.TempUnlockActivity::class.java)
                .putExtra("pkg", last)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        AppStore.ensureInit(applicationContext)
        val last = BlockStats.lastBlockedPkg()
        val activeUntil = UnlockExemptions.active(applicationContext)[last]
        when {
            last == null -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = "暂无最近拦截"
            }
            activeUntil != null && activeUntil > System.currentTimeMillis() -> {
                tile.state = Tile.STATE_ACTIVE
                val remainMin = ((activeUntil - System.currentTimeMillis()) / 60_000).toInt() + 1
                tile.subtitle = "临时解锁中 · 剩余 ${remainMin} 分（点按恢复）"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                val label = AppStore.findApp(last)?.label ?: last.substringAfterLast('.')
                tile.subtitle = "解锁 ${LockStrength.tempUnlockMin(applicationContext)} 分钟（${label}）"
            }
        }
        tile.updateTile()
    }
}
