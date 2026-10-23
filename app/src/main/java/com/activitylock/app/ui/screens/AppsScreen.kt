package com.activitylock.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.AppEntry
import com.activitylock.app.ui.components.AppIcon
import kotlinx.coroutines.launch

@Composable
fun AppsScreen(onOpenDetail: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val apps = AppStore.apps
    val loading = AppStore.appsLoading
    val q = query.trim().lowercase()

    Column(Modifier.fillMaxSize()) {
        Text(
            "应用",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = MaterialTheme.shapes.small,
            placeholder = { Text("搜索 App 名称 / 包名 / 界面类名") },
            singleLine = true
        )
        Spacer(Modifier.height(6.dp))

        if (loading) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("正在建立应用索引…", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }

        if (apps.isEmpty()) {
            // 重试后仍为空：MIUI 偶发限制应用列表读取，给手动重试入口
            LaunchedEffect(Unit) {
                if (AppStore.appsLoadFailed) return@LaunchedEffect
                AppStore.reloadApps() // 进入本页时若上次没读到，自动再试一次
            }
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Text(
                    if (AppStore.appsLoadFailed) "应用列表读取失败\n（系统偶发限制，点重试再试一次）"
                    else "暂无应用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { AppStore.reloadApps() }) { Text("重试") }
            }
            return@Column
        }

        if (q.isEmpty()) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(apps, key = { it.packageName }) { app ->
                    AppRow(app) { onOpenDetail(app.packageName) }
                }
            }
        } else {
            // 搜索模式：跨所有 App 直接匹配界面类名 + App 名称
            val matchedActivities = remember(apps, q) {
                apps.flatMap { app ->
                    app.activities.filter { act ->
                        act.cls.lowercase().contains(q)
                    }.map { app to it }
                }
            }
            val matchedApps = remember(apps, q) {
                apps.filter {
                    it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (matchedActivities.isNotEmpty()) {
                    item {
                        SectionHeader("匹配的界面（可直接开关）")
                    }
                    items(matchedActivities, key = { it.second.cls + it.first.packageName }) { (app, act) ->
                        ActivityToggleRow(
                            packageName = app.packageName,
                            cls = act.cls,
                            title = act.cls.substringAfterLast('.'),
                            // ★ v1.24.9：原来的「应用名 · 类名」是项目明令禁止的中点连接符，
                            //   与 SettingsScreen 的「Shizuku：…」对齐改用中文冒号。
                            subtitle = "${app.label}：${act.cls}",
                            showAppIcon = true
                        )
                    }
                }
                if (matchedApps.isNotEmpty()) {
                    item { SectionHeader("匹配的应用") }
                    items(matchedApps, key = { it.packageName }) { app ->
                        AppRow(app) { onOpenDetail(app.packageName) }
                    }
                }
                if (matchedActivities.isEmpty() && matchedApps.isEmpty()) {
                    item {
                        Text(
                            "没有匹配结果",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun AppRow(app: AppEntry, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.packageName, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "${app.activities.size} 界面",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
