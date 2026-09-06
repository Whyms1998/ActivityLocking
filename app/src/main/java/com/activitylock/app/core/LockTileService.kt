package com.activitylock.app.core

import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.runBlocking

import com.activitylock.app.UnlockGateActivity

/**
 * 两个快捷磁贴：全部上锁 / 全部解锁。
 */
abstract class BaseLockTileService : TileService() {

    abstract val targetLocked: Boolean

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        AppStore.ensureInit(applicationContext)
        val rules = AppStore.rules
        if (rules.isEmpty()) return
        if (!targetLocked && VerifyGate.isEnabled(applicationContext)) {
            // 自律模式：全解锁也要验证
            val i = android.content.Intent(applicationContext, UnlockGateActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            return
        }
        applyAll(rules, targetLocked)
    }

    private fun applyAll(rules: List<LockRule>, target: Boolean) {
        Thread {
            kotlinx.coroutines.runBlocking {
                for (r in rules) {
                    if (r.locked != target) {
                        LockEngine.apply(r.packageName, r.activityName, target)
                    }
                }
            }
            AmsController.writeRules()
            Handler(Looper.getMainLooper()).post { refresh() }
        }.start()
    }

    fun refresh() {
        val tile = qsTile ?: return
        AppStore.ensureInit(applicationContext)
        val rules = AppStore.rules
        if (rules.isEmpty()) {
            tile.state = Tile.STATE_INACTIVE
            tile.subtitle = "未配置"
        } else {
            val done = rules.all { it.locked == targetLocked }
            tile.state = if (done) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.subtitle = if (done) "已生效" else "点击执行"
        }
        tile.updateTile()
    }
}

class LockAllTileService : BaseLockTileService() {
    override val targetLocked: Boolean = true
}

class UnlockAllTileService : BaseLockTileService() {
    override val targetLocked: Boolean = false
}
