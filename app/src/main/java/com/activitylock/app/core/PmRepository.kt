package com.activitylock.app.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

data class ActivityEntry(
    val name: String,
    val cls: String,
    val isLauncher: Boolean,
)

data class AppEntry(
    val packageName: String,
    val label: String,
    val activities: List<ActivityEntry>,
)

object PmRepository {

    fun loadApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val result = mutableListOf<AppEntry>()
        val packages = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
        for (info in packages) {
            val pkg = info.packageName ?: continue
            val label = runCatching { pm.getApplicationLabel(info.applicationInfo).toString() }.getOrDefault(pkg)
            val launcherCls = pm.getLaunchIntentForPackage(pkg)?.component?.className
            val acts = info.activities
                ?.filter { it != null && it.name != null }
                ?.map { ActivityEntry(it.name.substringAfter('.'), it.name, it.name == launcherCls) }
                ?.sortedWith(compareByDescending<ActivityEntry> { it.isLauncher }.thenBy { it.cls })
                ?: emptyList()
            result.add(AppEntry(pkg, label, acts))
        }
        return result.sortedBy { it.label.lowercase() }
    }

    fun isDisabled(context: Context, pkg: String, cls: String): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(ComponentName(pkg, cls))
        // pm disable-user 写入 DISABLED_USER；pm disable 写入 DISABLED；两者都视为已锁定
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    }

    /** 常规应用包管理器无法直接看到"用户禁用"以外的情况时也适用。 */
    fun stateText(disabled: Boolean): String = if (disabled) "已禁用" else "正常"
}

fun Drawable.toAppBitmap(size: Int = 96): ImageBitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, size, size)
    draw(canvas)
    return bmp.asImageBitmap()
}
