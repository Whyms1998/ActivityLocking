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
        val packages = runCatching {
            pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
        }.getOrDefault(emptyList())
        for (info in packages) {
            val pkg = info.packageName ?: continue
            // compileSdk 36 起 ApplicationInfo 为可空类型
            val label = runCatching { pm.getApplicationLabel(info.applicationInfo!!).toString() }.getOrDefault(pkg)
            val launcherCls = runCatching { pm.getLaunchIntentForPackage(pkg)?.component?.className }.getOrNull()
            val acts = info.activities
                ?.filter { it != null && it.name != null }
                ?.map { ActivityEntry(it.name.substringAfter('.'), it.name, it.name == launcherCls) }
                ?.sortedWith(compareByDescending<ActivityEntry> { it.isLauncher }.thenBy { it.cls })
                ?: emptyList()
            result.add(AppEntry(pkg, label, acts))
        }
        // 兜底：包可见性被系统收窄时（AppsFilter 拦截），用 Shizuku 以 shell 身份补全
        if (result.size < MIN_EXPECTED_APPS) {
            val fromShell = loadAppsViaShizuku(context, result.map { it.packageName }.toSet())
            if (fromShell.isNotEmpty()) result.addAll(fromShell)
        }
        return result.sortedBy { it.label.lowercase() }
    }

    /**
     * Shizuku 兜底枚举：以 shell 身份执行 `pm list packages -3`（第三方包），
     * 再用 shell 读 `cmd package dump <pkg>` 拿不到 Activity——所以这里只补包名 + 标签，
     * 界面列表留空，由用户进入详情页时按需补齐。
     *
     * 这一层是"包可见性被 MIUI 收窄"时的保险，正常情况下不会走到。
     */
    private fun loadAppsViaShizuku(context: Context, known: Set<String>): List<AppEntry> {
        if (!ShizukuManager.granted()) return emptyList()
        return runCatching {
            val r = ShizukuManager.run("pm", "list", "packages", "-3")
            if (r.code != 0) return@runCatching emptyList<AppEntry>()
            val pm = context.packageManager
            r.output.lineSequence()
                .mapNotNull { line ->
                    val t = line.trim()
                    if (t.startsWith("package:")) t.removePrefix("package:").trim() else null
                }
                .filter { it.isNotBlank() && it !in known }
                .map { pkg ->
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                        .getOrDefault(pkg)
                    AppEntry(pkg, label, emptyList())
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    /** 低于此数量认为列表被系统收窄（正常设备第三方+系统应用远超此值）。 */
    private const val MIN_EXPECTED_APPS = 20

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
