package com.activitylock.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

    Box(modifier.size(size)) {
        if (bitmap != null) {
            Image(bitmap = bitmap!!, contentDescription = null, modifier = Modifier.size(size))
        } else {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}
