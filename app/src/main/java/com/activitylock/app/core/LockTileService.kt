package com.activitylock.app.core

import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.runBlocking

import com.activitylock.app.UnlockGateActivity

/**
 * `Tile.subtitle` 是 **API 29（Android 10）** 才加入的属性，而本 App 的 minSdk = 26。
 * 在 API 26~28 上直接赋值会抛 `NoSuchMethodError`（磁贴刷新时崩溃，Lint NewApi 报错）。
 * 低版本静默跳过即可 —— 副标题只是锦上添花，磁贴的 state / 图标 / 点击都照常工作。
 */
internal fun Tile.setSubtitleCompat(text: String) {
    if (android.os.Build.VERSION.SDK_INT >= 29) subtitle = text
}

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
        if (!targetLocked && !VerifyGate.bulkRequirement(applicationContext, rules).isNone) {
            // 自律模式：全局验证或任一界面单独设了解锁条件，全解锁都要先通过最强条件
            val i = android.content.Intent(applicationContext, UnlockGateActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            // HyperOS 会静默拦截磁贴服务的普通 startActivity，必须走 PendingIntent
            val pi = android.app.PendingIntent.getActivity(
                applicationContext, 0, i,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
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
            tile.setSubtitleCompat("未配置")
        } else {
            val done = rules.all { it.locked == targetLocked }
            tile.state = if (done) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.setSubtitleCompat(if (done) "已生效" else "点击执行")
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
