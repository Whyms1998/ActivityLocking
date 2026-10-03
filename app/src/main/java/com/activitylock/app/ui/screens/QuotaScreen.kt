package com.activitylock.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.LockRule
import com.activitylock.app.core.QuotaService
import com.activitylock.app.core.QuotaState
import com.activitylock.app.core.UsageTracker
import com.activitylock.app.ui.components.AppIcon

private fun refresh(ctx: android.content.Context) {
    com.activitylock.app.core.AmsController.writeRules()
    QuotaService.startIfQuotaConfigured(ctx)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotaScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val rules = AppStore.rules
    val quotaPkgs = QuotaState.quotaPackages(rules)
    val hasPermission = UsageTracker.hasPermission(context)
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("时长限额", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加限额")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!hasPermission) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("需要「使用情况访问权限」", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "统计各 App 当日使用时长必须授予此权限。点击后在列表中找到「应用锁」并允许。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { UsageTracker.openPermissionSettings(context) }) {
                            Text("去授权")
                        }
                    }
                }
            } else {
                Text(
                    "屏幕亮着时每分钟统计一次；超额后点图标会看到额度提示页，次日 0 点重置。\n" +
                        "手动解锁（通过该界面的解锁条件验证）优先于限额。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (quotaPkgs.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("还没有限额", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点右上角「＋」为已锁定规则的 App 设置每日使用时长上限，例如抖音每天 20 分钟。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(quotaPkgs.keys.sorted(), key = { it }) { pkg ->
                        val min = quotaPkgs[pkg] ?: 0
                        val used = if (hasPermission) UsageTracker.todayMinutes(context, pkg) else 0
                        val label = AppStore.findApp(pkg)?.label ?: pkg.substringAfterLast('.')
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(pkg, 42.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(label, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        if (used >= min) "今日已用 $used/$min 分钟 · 已超额"
                                        else "今日已用 $used/$min 分钟",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (used >= min) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = {
                                            (used.toFloat() / min.toFloat()).coerceIn(0f, 1f)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                IconButton(onClick = { removing = pkg }) {
                                    Icon(Icons.Default.Delete, contentDescription = "移除限额")
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (adding) {
        val candidates = rules.map { it.packageName }.distinct()
            .filter { it !in quotaPkgs.keys }
        QuotaAddDialog(
            candidates = candidates,
            onConfirm = { pkg, min ->
                for (r in rules.filter { it.packageName == pkg }) {
                    AppStore.updateQuota(r.packageName, r.activityName, min)
                }
                refresh(context)
                adding = false
            },
            onDismiss = { adding = false }
        )
    }

    removing?.let { pkg ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除限额？") },
            text = { Text("「${AppStore.findApp(pkg)?.label ?: pkg}」将不再限制每日使用时长。") },
            confirmButton = {
                TextButton(onClick = {
                    for (r in rules.filter { it.packageName == pkg }) {
                        AppStore.updateQuota(r.packageName, r.activityName, null)
                    }
                    refresh(context)
                    removing = null
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun QuotaAddDialog(
    candidates: List<String>,
    onConfirm: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(candidates.firstOrNull() ?: "") }
    var minutes by remember { mutableStateOf("20") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加限额") },
        text = {
            Column {
                if (candidates.isEmpty()) {
                    Text("所有已锁规则的 App 都已配置限额。")
                }
                candidates.forEach { pkg ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(
                            selected = selected == pkg,
                            onClick = { selected = pkg }
                        )
                        AppIcon(pkg, 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(AppStore.findApp(pkg)?.label ?: pkg)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("每日限额（分钟）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotBlank() && (minutes.toIntOrNull() ?: 0) in 1..1440,
                onClick = { onConfirm(selected, minutes.toInt()) }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
