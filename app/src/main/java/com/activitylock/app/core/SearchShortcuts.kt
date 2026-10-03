package com.activitylock.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 桌面「搜索直达」快捷方式：点击直接深链进搜索页。
 * 与系统小部件同级的体验——一键直达、不经过拦截流程；
 * 原应用图标保持锁定状态作为自律兜底。
 */
object SearchShortcuts {

    /**
     * 向桌面申请添加「<label> 搜索」快捷方式（系统会弹确认框）。
     * 返回是否申请成功（桌面不支持钉快捷方式时为 false）。
     */
    suspend fun pinSearchShortcut(ctx: Context, pkg: String, deepLink: String, label: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT < 26) return@runCatching false
                val icon = runCatching {
                    ctx.packageManager.getApplicationIcon(pkg).toAppBitmap(96)
                }.getOrNull()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val builder = ShortcutInfoCompat.Builder(ctx, "search_$pkg")
                    .setShortLabel("${label}搜索")
                    .setLongLabel("${label} · 搜索直达")
                    .setIntent(intent)
                if (icon != null) builder.setIcon(IconCompat.createWithBitmap(icon.asAndroidBitmap()))
                ShortcutManagerCompat.requestPinShortcut(ctx, builder.build(), null)
            }.getOrDefault(false)
        }
}
