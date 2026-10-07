package com.activitylock.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import com.activitylock.app.core.AppEntry
import com.activitylock.app.core.QuotaService
import com.activitylock.app.core.SessionCfg
import com.activitylock.app.core.QuotaState
import com.activitylock.app.core.UsageTracker
import com.activitylock.app.core.VerifyGate
import com.activitylock.app.ui.components.AppIcon
import com.activitylock.app.ui.components.UnlockVerifyDialog

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
    var addingSession by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    var pendingVerifyRemove by remember { mutableStateOf<String?>(null) }

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

            // 到点跳转：打开触发 App 连续 N 分钟后，自动打开学习 App（计时在系统级拦截器，切走即清零）
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("到点跳转", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "打开触发 App 连续满 N 分钟，自动打开学习 App；中途切走即重新计时。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { addingSession = true }) { Text("＋ 新增") }
                    }
                    AppStore.sessionCfgs.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(c.pkg, 30.dp)
                            if (c.mode == "lock") {
                                Text("  →  🔒", style = MaterialTheme.typography.labelMedium)
                            } else {
                                Text("  →  ", style = MaterialTheme.typography.labelMedium)
                                if (c.target.contains("://")) {
                                    Text("\uD83D\uDD17", style = MaterialTheme.typography.titleMedium)
                                } else {
                                    AppIcon(c.target.substringBefore('/'), 30.dp)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (c.mode == "lock") {
                                    "打开「" + (AppStore.findApp(c.pkg)?.label ?: c.pkg.substringAfterLast('.')) +
                                        "」连续 " + c.limitMin + " 分钟 → 当日锁定（次日恢复）"
                                } else {
                                    "打开「" + (AppStore.findApp(c.pkg)?.label ?: c.pkg.substringAfterLast('.')) +
                                        "」" + c.limitMin + " 分钟后自动打开「" + sessionTargetLabel(c.target) + "」" +
                                        if (c.maxJumps > 0) "· 当日 " + c.maxJumps + " 次后锁定" else ""
                                },
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = c.enabled,
                                onCheckedChange = { on ->
                                    AppStore.setSessionEnabled(c.pkg, on)
                                    refresh(context)
                                }
                            )
                            IconButton(onClick = {
                                AppStore.removeSession(c.pkg)
                                refresh(context)
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除")
                            }
                        }
                    }
                }
            }

            if (quotaPkgs.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).padding(32.dp),
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
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
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

    if (addingSession) {
        SessionAddDialog(
            onConfirm = { trigger, target, min, maxJ, mode ->
                AppStore.upsertSession(
                    SessionCfg(
                        pkg = trigger, target = target, limitMin = min,
                        maxJumps = maxJ, mode = mode
                    )
                )
                refresh(context)
                addingSession = false
            },
            onDismiss = { addingSession = false }
        )
    }

    removing?.let { pkg ->
        val over = pkg in QuotaState.overPackages(context)
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除限额？") },
            text = { Text(
                if (over) "「${AppStore.findApp(pkg)?.label ?: pkg}」今日已超额。移除限额将立刻恢复该 App 的使用，需要先通过验证。"
                else "「${AppStore.findApp(pkg)?.label ?: pkg}」将不再限制每日使用时长。"
            ) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    if (over) pendingVerifyRemove = pkg else {
                        for (r in rules.filter { it.packageName == pkg }) {
                            AppStore.updateQuota(r.packageName, r.activityName, null)
                        }
                        refresh(context)
                    }
                }) { Text(if (over) "验证并移除" else "移除") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }
        )
    }

    pendingVerifyRemove?.let { pkg ->
        UnlockVerifyDialog(
            requirement = VerifyGate.bulkRequirement(context, AppStore.rules),
            title = "移除限额验证",
            hint = "该 App 今日已超额，移除限额等于提前解锁它",
            onConfirmed = {
                pendingVerifyRemove = null
                for (r in rules.filter { it.packageName == pkg }) {
                    AppStore.updateQuota(r.packageName, r.activityName, null)
                }
                QuotaState.clearOver(context, pkg)
                refresh(context)
            },
            onDismiss = { pendingVerifyRemove = null }
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

private fun sessionTargetLabel(target: String): String {
    val t = target.trim()
    return when {
        t.contains("://") -> "深链 " + t.take(16) + "…"
        t.contains("/") ->
            (AppStore.findApp(t.substringBefore('/'))?.label
                ?: t.substringBefore('/').substringAfterLast('.'))
        else -> AppStore.findApp(t)?.label ?: t.substringAfterLast('.')
    }
}

@Composable
private fun SessionAddDialog(
    onConfirm: (String, String, Int, Int, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query1 by remember { mutableStateOf("") }
    var query2 by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<String?>(null) }
    var minutes by remember { mutableStateOf("1") }
    var maxJumps by remember { mutableStateOf(3) }
    var mode by remember { mutableStateOf("jump") }

    fun candidates(q: String, exclude: String?): List<AppEntry> =
        AppStore.apps
            .filter {
                (it.label.contains(q, true) || it.packageName.contains(q, true)) &&
                    it.packageName != exclude
            }
            .sortedBy { it.label }
            .take(40)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增到点跳转") },
        text = {
            Column {
                Text("刷满后怎么处理", style = MaterialTheme.typography.labelMedium)
                Row {
                    FilterChip(
                        selected = mode == "jump",
                        onClick = { mode = "jump" },
                        label = { Text("跳去学习 App") }
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = mode == "lock",
                        onClick = { mode = "lock" },
                        label = { Text("直接锁定") }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("触发 App（要限制刷的）", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = query1,
                    onValueChange = { query1 = it },
                    placeholder = { Text("搜索 App") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                PickerList(candidates(query1, target), trigger) { trigger = it }
                if (mode == "jump") {
                    Spacer(Modifier.height(8.dp))
                    Text("跳转目标（学习去向）", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = query2,
                        onValueChange = { query2 = it },
                        placeholder = { Text("搜索 App") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    PickerList(candidates(query2, trigger), target) { target = it }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("连续使用（分钟）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (mode == "jump") {
                    Spacer(Modifier.height(8.dp))
                    Text("当日第 N 次跳转后锁定该 App（次日恢复）", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Row {
                        listOf(0 to "不锁定", 2 to "2 次", 3 to "3 次", 5 to "5 次").forEach { (n, label) ->
                            FilterChip(
                                selected = maxJumps == n,
                                onClick = { maxJumps = n },
                                label = { Text(label) }
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "锁定后当日无法打开，次日 0 点恢复；也可在 app 内通过验证后关闭本规则提前解锁。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = trigger != null && (mode == "lock" || target != null) &&
                    (minutes.toIntOrNull() ?: 0) in 1..240,
                onClick = { onConfirm(trigger!!, target ?: "", minutes.toInt(), maxJumps, mode) }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PickerList(
    pkgs: List<AppEntry>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Column(
        Modifier
            .heightIn(max = 140.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (pkgs.isEmpty()) {
            Text(
                "没有匹配的应用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        pkgs.forEach { app ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(app.packageName) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected == app.packageName,
                    onClick = { onSelect(app.packageName) }
                )
                AppIcon(app.packageName, 26.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}
