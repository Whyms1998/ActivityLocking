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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
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
import com.activitylock.app.core.LockSchedule
import com.activitylock.app.core.ScheduleEngine
import com.activitylock.app.core.VerifyGate
import com.activitylock.app.ui.components.UnlockVerifyDialog

private fun format(h: Int, m: Int) = "%02d:%02d".format(h, m)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val schedules = AppStore.schedules
    val active = ScheduleEngine.anyActive(schedules)
    var editing by remember { mutableStateOf<LockSchedule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var killEnabled by remember { mutableStateOf(ScheduleEngine.screenOffKillEnabled(context)) }
    var pendingVerifyDelete by remember { mutableStateOf<LockSchedule?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("定时计划", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Default.Add, contentDescription = "新建计划")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("息屏自动清栈", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "息屏时结束所有锁定中的 App",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = killEnabled, onCheckedChange = {
                            killEnabled = it
                            ScheduleEngine.setScreenOffKill(context, it)
                        })
                    }
                }
            }

            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = if (active) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    if (active) "当前：计划内，全部规则已上锁"
                    // ★ 规范 §1：原来「当前：计划外 · 切换最迟 15 分钟内自动生效」是中点串状态+说明
                    else "当前：计划外 · 最迟 15 分钟生效",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            if (schedules.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("还没有定时计划", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点右上角「＋」新建，例如 23:00-07:00 睡眠模式：\n进入计划时全部规则自动上锁，退出后恢复原状。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(schedules, key = { it.id }) { s ->
                        ScheduleRow(
                            schedule = s,
                            onToggle = { AppStore.upsertSchedule(s.copy(enabled = it)) },
                            onEdit = { editing = s },
                            onDelete = {
                                // 有生效窗口时删除计划=提前结束并按快照解锁，需要验证
                                if (ScheduleEngine.anyActive(AppStore.schedules)) {
                                    pendingVerifyDelete = s
                                } else {
                                    AppStore.removeSchedule(s.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    pendingVerifyDelete?.let { s ->
        UnlockVerifyDialog(
            requirement = VerifyGate.bulkRequirement(context, AppStore.rules),
            title = "删除计划验证",
            hint = "当前有生效中的计划窗口，删除将提前结束并按快照恢复解锁状态",
            onConfirmed = {
                pendingVerifyDelete = null
                AppStore.removeSchedule(s.id)
            },
            onDismiss = { pendingVerifyDelete = null }
        )
    }

    if (creating || editing != null) {
        ScheduleEditorDialog(
            initial = editing,
            onSave = {
                AppStore.upsertSchedule(it)
                creating = false
                editing = null
                // 保存后立即对账（比如新计划当前正处在窗口内）
                ScheduleEngine.reconcile(context, killNow = false)
            },
            onDismiss = { creating = false; editing = null }
        )
    }
}

@Composable
private fun ScheduleRow(
    schedule: LockSchedule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    schedule.label.ifBlank { "定时计划" },
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "${format(schedule.startHour, schedule.startMinute)} — " +
                        format(schedule.endHour, schedule.endMinute),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = schedule.enabled, onCheckedChange = onToggle)
            TextButton(onClick = onEdit) { Text("编辑") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleEditorDialog(
    initial: LockSchedule?,
    onSave: (LockSchedule) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var vDays by remember { mutableStateOf(initial?.weekdays) }
    var startHour by remember { mutableStateOf(initial?.startHour ?: 23) }
    var startMinute by remember { mutableStateOf(initial?.startMinute ?: 0) }
    var endHour by remember { mutableStateOf(initial?.endHour ?: 7) }
    var endMinute by remember { mutableStateOf(initial?.endMinute ?: 0) }
    var picking by remember { mutableStateOf<String?>(null) } // "start" | "end"

    if (picking != null) {
        val isStart = picking == "start"
        val state = rememberTimePickerState(
            initialHour = if (isStart) startHour else endHour,
            initialMinute = if (isStart) startMinute else endMinute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(if (isStart) "开始时间" else "结束时间") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    if (isStart) { startHour = state.hour; startMinute = state.minute }
                    else { endHour = state.hour; endMinute = state.minute }
                    picking = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("取消") } }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建计划" else "编辑计划") },
        text = {
            // ★ 名称输入框会唤起键盘 → 让 Dialog window 随键盘收缩
            com.activitylock.app.ui.components.DialogImePan()
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { picking = "start" }) {
                        Text(format(startHour, startMinute), style = MaterialTheme.typography.titleLarge)
                    }
                    Text("到", modifier = Modifier.padding(horizontal = 12.dp))
                    TextButton(onClick = { picking = "end" }) {
                        Text(format(endHour, endMinute), style = MaterialTheme.typography.titleLarge)
                    }
                }
                Text(
                    "窗口内全部规则自动上锁；支持跨午夜。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text("生效星期", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("一","二","三","四","五","六","日").forEachIndexed { idx, d ->
                        val day = idx + 1
                        FilterChip(
                            selected = vDays?.contains(day) ?: true,
                            onClick = {
                                val cur = vDays ?: (1..7).toSet()
                                vDays = if (cur.contains(day)) cur - day else cur + day
                            },
                            label = { Text(d) }
                        )
                    }
                }
                Text(
                    "不选任何星期 = 计划停用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    LockSchedule(
                        id = initial?.id ?: "",
                        label = label.trim(),
                        startHour = startHour, startMinute = startMinute,
                        endHour = endHour, endMinute = endMinute,
                        enabled = initial?.enabled ?: true,
                        weekdays = vDays?.takeIf { it.isNotEmpty() },
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
