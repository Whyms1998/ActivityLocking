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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AmsController
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.LockEngine
import com.activitylock.app.core.LockOutcome
import com.activitylock.app.core.LockRule
import com.activitylock.app.core.VerifyGate
import com.activitylock.app.ui.components.AppIcon
import com.activitylock.app.ui.components.UnlockVerifyDialog
import kotlinx.coroutines.launch

@Composable
private fun VerifyModeRow(selected: Boolean, title: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun key(r: LockRule) = "${r.packageName}/${r.activityName}"

@Composable
private fun GroupHeader(
    pkg: String,
    count: Int,
    lockedCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val label = AppStore.findApp(pkg)?.label ?: pkg
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onToggle),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(pkg, 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    "$count 个界面 · $lockedCount 个锁定中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Text(
                if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp)
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 0f else 180f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen() {
    val rules = AppStore.rules
    val actual = remember { mutableStateMapOf<String, Boolean>() }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<LockRule?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    val ctx = LocalContext.current
    var deleting by remember { mutableStateOf<LockRule?>(null) }
    var pendingUnlock by remember { mutableStateOf<LockRule?>(null) }
    var pendingDelete by remember { mutableStateOf<LockRule?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    fun refresh() {
        for (r in rules) {
            if (!r.block) {
                actual["${r.packageName}/${r.activityName}"] =
                    AppStore.isDisabled(r.packageName, r.activityName)
            }
        }
    }

    LaunchedEffect(rules, refreshTick) { refresh() }

    fun onOutcome(o: LockOutcome) {
        when {
            !o.locked && o.mode == "none" -> AppStore.toast("操作失败：${o.detail}")
            o.locked && o.mode == "pm" -> AppStore.toast("锁定完成（组件已禁用）")
            o.locked && o.mode == "block" -> AppStore.toast("已启用拦截：该界面将无法启动")
            else -> AppStore.toast("已解锁（条目保留，可随时再锁定）")
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("收藏夹", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { refreshTick++ }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新状态")
            }
        }

        if (rules.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("还没有收藏任何界面", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "去「应用」页找到目标 App，选择要锁定的界面。\n锁定的界面会自动出现在这里。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val grouped = remember(rules) {
                rules.sortedBy { it.createdAt }.groupBy { it.packageName }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                grouped.forEach { (pkg, groupRules) ->
                    item(key = "hdr_$pkg") {
                        GroupHeader(
                            pkg = pkg,
                            count = groupRules.size,
                            lockedCount = groupRules.count { r ->
                                if (r.block) r.locked else (actual[key(r)] ?: false)
                            },
                            expanded = expanded[pkg] != false,
                            onToggle = { expanded[pkg] = !(expanded[pkg] != false) }
                        )
                    }
                    if (expanded[pkg] != false) {
                        items(groupRules, key = { key(it) }) { rule ->
                    val key = "${rule.packageName}/${rule.activityName}"
                    // block 模式以规则里的 locked 为准；pm 模式以系统实际状态为准
                    val pmDisabled = !rule.block && (actual[key] ?: false)
                    val locked = if (rule.block) rule.locked else pmDisabled
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(rule.packageName, 42.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    rule.note.ifBlank { rule.activityName.substringAfterLast('.') },
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    rule.activityName,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Surface(
                                        shape = MaterialTheme.shapes.extraSmall,
                                        color = if (locked) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            when {
                                                rule.block && locked -> "拦截中"
                                                rule.block -> "已暂停"
                                                pmDisabled -> "已锁定"
                                                else -> "未锁定"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (locked) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                    if (!rule.block && rule.locked && !pmDisabled) {
                                        Spacer(Modifier.width(8.dp))
                                        AssistChip(
                                            onClick = {
                                                busy[key] = true
                                                scope.launch {
                                                    onOutcome(LockEngine.apply(rule.packageName, rule.activityName, true, rule.redirectUri))
                                                    refresh()
                                                    busy[key] = false
                                                }
                                            },
                                            label = { Text("修复", style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                                rule.redirectUri?.let { uri ->
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "↗ 跳转：$uri",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                rule.verifyMode?.let { mode ->
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "🔓 解锁条件：" + when (mode) {
                                            VerifyGate.MODE_NONE -> "无需验证"
                                            VerifyGate.METHOD_DELAY -> "等待 ${(rule.verifyDelaySec ?: VerifyGate.delaySeconds(ctx))} 秒"
                                            VerifyGate.METHOD_PASSWORD -> "输入密码"
                                            else -> "跟随全局"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                            IconButton(onClick = { editing = rule }) {
                                Icon(Icons.Default.Edit, contentDescription = "备注")
                            }
                            IconButton(onClick = {
                                // 删除"锁定中"的条目同样触发该界面的解锁条件，防止绕过
                                if (locked && !VerifyGate.requirementFor(ctx, rule).isNone) {
                                    pendingDelete = rule
                                } else {
                                    deleting = rule
                                }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除条目")
                            }
                            Switch(
                                checked = locked,
                                enabled = busy[key] != true,
                                onCheckedChange = { wantLock ->
                                    if (wantLock) {
                                        busy[key] = true
                                        scope.launch {
                                            onOutcome(LockEngine.apply(rule.packageName, rule.activityName, true, rule.redirectUri))
                                            refresh()
                                            busy[key] = false
                                        }
                                    } else if (!VerifyGate.requirementFor(ctx, rule).isNone) {
                                        // 自律模式：按该界面的解锁条件验证
                                        pendingUnlock = rule
                                    } else {
                                        busy[key] = true
                                        scope.launch {
                                            onOutcome(LockEngine.apply(rule.packageName, rule.activityName, false))
                                            refresh()
                                            busy[key] = false
                                        }
                                    }
                                }
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

    pendingDelete?.let { rule ->
        UnlockVerifyDialog(
            requirement = VerifyGate.requirementFor(ctx, rule),
            title = "删除验证",
            onConfirmed = {
                pendingDelete = null
                deleting = rule
            },
            onDismiss = { pendingDelete = null }
        )
    }

    deleting?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除条目？") },
            text = {
                Text(
                    "「${rule.note.ifBlank { rule.activityName.substringAfterLast('.') }}」将从收藏夹移除。\n" +
                        "历史记录中仍可找回并重新锁定。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppStore.removeRule(rule.packageName, rule.activityName)
                    AmsController.writeRules()
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }

    pendingUnlock?.let { rule ->
        UnlockVerifyDialog(
            requirement = VerifyGate.requirementFor(ctx, rule),
            onConfirmed = {
                pendingUnlock = null
                busy[key(rule)] = true
                scope.launch {
                    onOutcome(LockEngine.apply(rule.packageName, rule.activityName, false))
                    refresh()
                    busy[key(rule)] = false
                }
            },
            onDismiss = { pendingUnlock = null }
        )
    }

    editing?.let { rule ->
        var text by remember(rule) { mutableStateOf(rule.note) }
        var redirect by remember(rule) { mutableStateOf(rule.redirectUri ?: "") }
        var vMode by remember(rule) { mutableStateOf(rule.verifyMode) }
        var vDelay by remember(rule) {
            mutableStateOf(((rule.verifyDelaySec ?: VerifyGate.delaySeconds(ctx))).toString())
        }
        var vPwd by remember(rule) { mutableStateOf(rule.verifyPassword ?: "") }
        var quota by remember(rule) { mutableStateOf(rule.quotaMin?.toString() ?: "") }
        val globalReq = VerifyGate.globalRequirement(ctx)
        val globalDesc = when (globalReq.mode) {
            VerifyGate.MODE_NONE -> "已关闭"
            VerifyGate.METHOD_DELAY -> "等待${globalReq.delaySec}秒"
            else -> "密码"
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑界面") },
            text = {
                Column {
                    Text(rule.activityName, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("备注") },
                        placeholder = { Text("例如：小红书首页 / 抖音推荐流") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = redirect,
                        onValueChange = { redirect = it },
                        label = { Text("拦截后跳转（留空则仅拦截）") },
                        placeholder = { Text("深链 xhsdiscover://search 或界面类名") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = quota,
                        onValueChange = { quota = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("每日限额（分钟，留空不限）") },
                        placeholder = { Text("例如 20：超额后当天拦截此 App") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("解锁条件", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    VerifyModeRow(
                        selected = vMode == null,
                        title = "跟随全局（当前：$globalDesc）",
                        onClick = { vMode = null }
                    )
                    VerifyModeRow(
                        selected = vMode == VerifyGate.MODE_NONE,
                        title = "无需验证",
                        onClick = { vMode = VerifyGate.MODE_NONE }
                    )
                    VerifyModeRow(
                        selected = vMode == VerifyGate.METHOD_DELAY,
                        title = "等待倒计时",
                        onClick = { vMode = VerifyGate.METHOD_DELAY }
                    )
                    if (vMode == VerifyGate.METHOD_DELAY) {
                        Row(
                            Modifier.padding(start = 40.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = {
                                vDelay = (((vDelay.toIntOrNull() ?: 5) - 5).coerceAtLeast(5)).toString()
                            }) { Text("−") }
                            Text(
                                "${vDelay.toIntOrNull() ?: 5} 秒",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                            TextButton(onClick = {
                                vDelay = (((vDelay.toIntOrNull() ?: 5) + 5).coerceAtMost(600)).toString()
                            }) { Text("＋") }
                        }
                    }
                    VerifyModeRow(
                        selected = vMode == VerifyGate.METHOD_QUIZ,
                        title = "答对一道口算题",
                        onClick = { vMode = VerifyGate.METHOD_QUIZ }
                    )
                    VerifyModeRow(
                        selected = vMode == VerifyGate.METHOD_PASSWORD,
                        title = "输入密码",
                        onClick = { vMode = VerifyGate.METHOD_PASSWORD }
                    )
                    if (vMode == VerifyGate.METHOD_PASSWORD) {
                        OutlinedTextField(
                            value = vPwd,
                            onValueChange = { vPwd = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 40.dp, top = 4.dp, end = 16.dp),
                            label = { Text(if (vPwd.isBlank()) "留空则使用全局密码" else "该界面专用密码") },
                            singleLine = true
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        AppStore.upsertPreset(
                            com.activitylock.app.core.ConfigPreset(
                                id = "",
                                label = text.ifBlank {
                                    rule.activityName.substringAfterLast('.')
                                },
                                packageName = rule.packageName,
                                activityName = rule.activityName,
                                redirectUri = redirect.trim().takeIf { it.isNotBlank() },
                                note = text.trim(),
                                builtin = false,
                            )
                        )
                        AppStore.toast("已保存到方案库·我的方案")
                        editing = null
                    }) { Text("存为预设（可在方案库复用/导出）") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AppStore.updateNote(rule.packageName, rule.activityName, text)
                    AppStore.updateRedirect(rule.packageName, rule.activityName, redirect)
                    AppStore.updateVerify(
                        rule.packageName, rule.activityName,
                        vMode, vDelay.trim().toIntOrNull(), vPwd
                    )
                    AppStore.updateQuota(rule.packageName, rule.activityName, quota.trim().toIntOrNull())
                    // 跳转目标/超额状态影响 Watcher 规则，立即同步；限额需要守护服务
                    AmsController.writeRules()
                    com.activitylock.app.core.QuotaService.startIfQuotaConfigured(ctx)
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            }
        )
    }
}
