package com.activitylock.app.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.SchemePresets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemeLibraryScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val q = query.trim()

    val filtered = if (q.isEmpty()) SchemePresets.all
    else SchemePresets.all.filter { it.name.contains(q, true) || it.uri.contains(q, true) }

    // 输入类似 scheme 的关键字时，解析本机可响应的应用
    val handlers = remember(q) {
        if (q.length < 2 || q.contains(" ")) emptyList()
        else runCatching {
            val uri = if (q.contains("://")) q else "$q://probe"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                    .map { it.loadLabel(ctx.packageManager).toString() to (it.activityInfo?.name ?: "") }
        }.getOrDefault(emptyList())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("URL Scheme 库", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                shape = MaterialTheme.shapes.small,
                placeholder = { Text("搜索常用 Scheme，或输入 scheme 查询本机响应") },
                singleLine = true
            )
            LazyColumn(Modifier.fillMaxSize()) {
                if (filtered.isNotEmpty()) {
                    item {
                        Text(
                            "常用 Scheme",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(filtered, key = { it.uri }) { p ->
                        SchemeRow(p.name, p.uri)
                    }
                }
                if (handlers.isNotEmpty()) {
                    item {
                        Text(
                            "本机可响应 “$q” 的应用",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(handlers, key = { it.second + it.first }) { (label, cls) ->
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    cls,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                TextButton(onClick = {
                                    val uri = if (q.contains("://")) q else "$q://probe"
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }) { Text("打开测试") }
                            }
                        }
                    }
                }
                if (filtered.isEmpty() && handlers.isEmpty()) {
                    item {
                        Text(
                            "没有匹配结果。输入 scheme 名称（如 xhsdiscover）可查询本机响应。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SchemeRow(name: String, uri: String) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(
                uri,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row {
                TextButton(onClick = {
                    AppStore.copyText(uri)
                    AppStore.toast("已复制：$uri")
                }) { Text("复制") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    runCatching {
                        AppStore.context().startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure { AppStore.toast("无法打开：${it.message}") }
                }) { Text("打开测试") }
            }
        }
    }
}
