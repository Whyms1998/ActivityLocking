package com.activitylock.app.ui.screens

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

private fun key(r: LockRule) = "${r.packageName}/${r.activityName}"

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
            LazyColumn(Modifier.fillMaxSize()) {
                items(rules, key = { "${it.packageName}/${it.activityName}" }) { rule ->
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
                                                    onOutcome(LockEngine.apply(rule.packageName, rule.activityName, true))
                                                    refresh()
                                                    busy[key] = false
                                                }
                                            },
                                            label = { Text("修复", style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { editing = rule }) {
                                Icon(Icons.Default.Edit, contentDescription = "备注")
                            }
                            IconButton(onClick = { deleting = rule }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除条目")
                            }
                            Switch(
                                checked = locked,
                                enabled = busy[key] != true,
                                onCheckedChange = { wantLock ->
                                    if (wantLock) {
                                        busy[key] = true
                                        scope.launch {
                                            onOutcome(LockEngine.apply(rule.packageName, rule.activityName, true))
                                            refresh()
                                            busy[key] = false
                                        }
                                    } else if (VerifyGate.isEnabled(ctx)) {
                                        // 自律模式：解锁前需要验证
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
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("界面备注") },
            text = {
                Column {
                    Text(rule.activityName, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("例如：小红书首页 / 抖音推荐流") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AppStore.updateNote(rule.packageName, rule.activityName, text)
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            }
        )
    }
}
