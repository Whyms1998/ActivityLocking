package com.activitylock.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.toAppBitmap

@Composable
fun AppIcon(packageName: String, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName).toAppBitmap(96)
        }.getOrNull()
    }
    Box(modifier.size(size)) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(size))
        } else {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}
