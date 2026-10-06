package com.activitylock.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.LockEngine
import com.activitylock.app.ui.components.AppIcon
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var refreshTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    val history = AppStore.history
    val q = query.trim().lowercase()
    val filtered = if (q.isEmpty()) history
    else history.filter {
        it.packageName.lowercase().contains(q) ||
            it.activityName.lowercase().contains(q) ||
            it.note.lowercase().contains(q)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("锁定历史", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (history.isNotEmpty()) {
                        TextButton(onClick = { AppStore.clearHistory() }) { Text("清空") }
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
                placeholder = { Text("搜索 App / 类名 / 备注") },
                singleLine = true
            )

            if (filtered.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (history.isEmpty()) "暂无锁定记录\n每次锁定/解锁都会自动留痕"
                        else "没有匹配结果",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // 按天分组：今天默认展开，更早的折叠为一行（瘦身：一眼看到今天，历史按需展开）
                val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }
                val yesterday = remember {
                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        .format(Date(System.currentTimeMillis() - 86_400_000L))
                }
                val groups = remember(filtered) {
                    filtered.groupBy { timeFmt.format(Date(it.time)).substring(0, 10) }
                }
                var expandedDays by remember { mutableStateOf(setOf(today)) }
                LazyColumn(Modifier.fillMaxSize()) {
                    groups.forEach { (day, list) ->
                        val isOpen = day in expandedDays
                        item(key = "hdr_$day") {
                            val dayLabel = when (day) {
                                today -> "今天"
                                yesterday -> "昨天"
                                else -> day.substring(5).replace('-', '月') + "日"
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedDays =
                                            if (isOpen) expandedDays - day else expandedDays + day
                                    }
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    dayLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    if (isOpen) "收起" else "${list.size} 条记录",
                                    style = MaterialTheme.typography.labelSmall
                                        .copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (isOpen) {
                            items(list, key = { "${it.packageName}/${it.activityName}/${it.time}" }) { h ->
                        val isFav = AppStore.hasRule(h.packageName, h.activityName)
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(h.packageName, 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        h.note.ifBlank { h.activityName.substringAfterLast('.') },
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        h.activityName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        buildString {
                                            append(timeFmt.format(Date(h.time)))
                                            append(" · ")
                                            append(if (h.action == "LOCK") "锁定" else "解锁")
                                            append(" · ")
                                            append(if (h.mode == "block") "拦截" else "组件")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = {
                                    AppStore.copyText("${h.packageName}/${h.activityName}")
                                    AppStore.toast("已复制")
                                }) { Text("复制") }
                                if (!isFav) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            LockEngine.apply(h.packageName, h.activityName, true)
                                            refreshTick++
                                        }
                                    }) { Text("锁定") }
                                } else {
                                    Text(
                                        "已收藏",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                                TextButton(onClick = {
                                    AppStore.removeHistory(h.packageName, h.activityName)
                                    refreshTick++
                                }) { Text("删除") }
                            }
                        }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}
