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
                                        // ★ 规范 §1：原来是「14:22 · 锁定 · 拦截」——三段并列无标签，
                                        //   最容易读错的是"哪个词对应哪个位置"。改成「时间 + 动作（模式）」。
                                        buildString {
                                            append(timeFmt.format(Date(h.time)))
                                            append("  ")
                                            append(if (h.action == "LOCK") "锁定" else "解锁")
                                            append(if (h.mode == "block") "（拦截）" else "")
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
                                            // ★ 重新锁定必须带上跳转目标，否则「删除 → 从历史找回」
                                            //   会把「点图标跳搜索页」退化成「静默拦截」。
                                            //   历史里没有记录（旧版本数据）时，按**内置方案里
                                            //   同一 (包名, 界面) 的目标**兜底 —— 不能盲目用
                                            //   SchemePresets.suggestFor(pkg)：像微信这种包里
                                            //   含多个深链，会把「纯拦截朋友圈」误改成「跳扫一扫」。
                                            val recorded = h.redirectUri
                                            val builtin = com.activitylock.app.core.BuiltinPresets.all
                                                .firstOrNull {
                                                    it.packageName == h.packageName &&
                                                        it.activityName == h.activityName
                                                }?.redirectUri
                                            val uri = recorded ?: builtin
                                            LockEngine.apply(h.packageName, h.activityName, true, uri)
                                            AppStore.toast(
                                                when {
                                                    recorded != null -> "已重新锁定：点图标直达搜索页"
                                                    builtin != null -> "已重新锁定，并按内置方案恢复跳转：$builtin"
                                                    else -> "已重新锁定（该条目为纯拦截，无跳转）"
                                                }
                                            )
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
