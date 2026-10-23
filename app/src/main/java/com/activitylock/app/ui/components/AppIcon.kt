package com.activitylock.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.toAppBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用图标全局缓存：跨界面共享，避免列表滚动时在主线程反复
 * 跨进程加载 Drawable 并新建位图（这是列表滑动卡顿的主因）。
 */
object IconCache {
    private val lru = androidx.collection.LruCache<String, ImageBitmap>(256)

    fun get(pkg: String): ImageBitmap? = lru.get(pkg)

    fun put(pkg: String, bitmap: ImageBitmap) {
        lru.put(pkg, bitmap)
    }
}

/**
 * 真实 launcher 图标（文档 §8.11）。
 *
 * 三条硬性要求：
 *  1. **必须来自 `PackageManager`** —— 禁止色块 + 应用名首字（「微」「抖」）之类
 *     的文字块充当图标；
 *  2. 加载失败回退到**灰色占位块**，同样不是文字；
 *  3. **统一圆角裁切** —— 各家图标本身形状不一（圆角矩形 / 圆形 / 异形），
 *     裁成同一圆角后整列网格的视觉节奏才整齐。
 */
@Composable
fun AppIcon(packageName: String, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf(IconCache.get(packageName)) }

    LaunchedEffect(packageName) {
        if (bitmap == null) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager.getApplicationIcon(packageName).toAppBitmap(96)
                }.getOrNull()
            }
            if (loaded != null) {
                IconCache.put(packageName, loaded)
                bitmap = loaded
            }
        }
    }

    // 圆角随尺寸缩放：50dp 网格图标 ≈ 14dp 圆角（文档 §6.5 icon_radius_app）
    val corner = (size.value * 0.28f).dp
    Box(modifier.size(size).clip(RoundedCornerShape(corner))) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.size(size).clip(RoundedCornerShape(corner))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
        }
    }
}
