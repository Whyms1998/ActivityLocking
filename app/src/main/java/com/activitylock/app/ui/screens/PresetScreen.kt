package com.activitylock.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.BuiltinPresets
import com.activitylock.app.core.ConfigPreset
import com.activitylock.app.core.LockEngine
import com.activitylock.app.ui.components.AppIcon
import kotlinx.coroutines.launch

private enum class PresetStatus { NotInstalled, NotImported, Active, NeedsRestore }

private fun statusOf(p: ConfigPreset, installed: Set<String>): PresetStatus {
    if (p.packageName !in installed) return PresetStatus.NotInstalled
    val rule = AppStore.rules.firstOrNull {
        it.packageName == p.packageName && it.activityName == p.activityName
    }
    if (rule == null) return PresetStatus.NotImported
    val redirectMatch = (rule.redirectUri ?: "").trim() == (p.redirectUri ?: "").trim()
    return if (rule.locked && redirectMatch) PresetStatus.Active else PresetStatus.NeedsRestore
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val installed = remember { AppStore.installedPackages() }
    var busyId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ConfigPreset?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ConfigPreset?>(null) }

    fun applyPreset(p: ConfigPreset) {
        busyId = p.id
        scope.launch {
            // 恢复纯拦截方案时，先清掉遗留的自定义跳转
            if (p.redirectUri.isNullOrBlank()) {
                AppStore.updateRedirect(p.packageName, p.activityName, null)
            }
            val o = LockEngine.apply(p.packageName, p.activityName, true, p.redirectUri)
            if (o.locked) {
                if (p.note.isNotBlank()) AppStore.updateNote(p.packageName, p.activityName, p.note)
                AppStore.toast("「${p.label}」已配置")
            } else {
                AppStore.toast("「${p.label}」配置失败：${o.detail}")
            }
            busyId = null
        }
    }

    fun pinShortcut(p: ConfigPreset) {
        val uri = p.redirectUri ?: return
        scope.launch {
            val label = AppStore.findApp(p.packageName)?.label ?: p.label
            val ok = com.activitylock.app.core.SearchShortcuts.pinSearchShortcut(
                context, p.packageName, uri, label
            )
            AppStore.toast(if (ok) "已在桌面创建「${label}搜索」直达" else "桌面不支持添加快捷方式")
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("方案库", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val json = AppStore.exportPresetsJson()
                        if (json.length <= 4) {
                            AppStore.toast("没有可导出的用户方案")
                        } else {
                            AppStore.copyText(json)
                            AppStore.toast("用户方案已导出到剪贴板")
                        }
                    }) { Text("导出") }
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
                        val n = AppStore.importPresets(text)
                        AppStore.toast(if (n < 0) "剪贴板内容不是有效方案" else "已导入 $n 条方案")
                    }) { Text("导入") }
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Default.Add, contentDescription = "新建方案")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "一键配置「锁界面 + 跳转」。改坏或误删后回到这里点「恢复」即可；" +
                    "自定义方案可导出到剪贴板分享，导入后自动转为用户方案。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(Modifier.fillMaxSize()) {
                item { SectionHeader("内置方案") }
                items(BuiltinPresets.all, key = { "b_" + it.id }) { p ->
                    PresetRow(p, installed, busyId == p.id, { applyPreset(p) },
                        onPin = if (p.redirectUri != null) { { pinShortcut(p) } } else null,
                        onEdit = null, onDelete = null)
                }
                if (AppStore.userPresets.isNotEmpty()) {
                    item { SectionHeader("我的方案") }
                    items(AppStore.userPresets, key = { "u_" + it.id }) { p ->
                        PresetRow(p, installed, busyId == p.id, { applyPreset(p) },
                            onPin = if (p.redirectUri != null) { { pinShortcut(p) } } else null,
                            onEdit = { editing = it },
                            onDelete = { deleting = it })
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (creating || editing != null) {
        PresetEditorDialog(
            initial = editing,
            onSave = { p ->
                AppStore.upsertPreset(p)
                creating = false
                editing = null
            },
            onDismiss = { creating = false; editing = null }
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除方案？") },
            text = { Text("「${p.label}」将从我的方案中移除（不影响已锁定的界面）。") },
            confirmButton = {
                TextButton(onClick = { AppStore.removePreset(p.id); deleting = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun PresetRow(
    p: ConfigPreset,
    installed: Set<String>,
    busy: Boolean,
    onApply: () -> Unit,
    onPin: (() -> Unit)?,
    onEdit: ((ConfigPreset) -> Unit)?,
    onDelete: ((ConfigPreset) -> Unit)?,
) {
    val status = statusOf(p, installed)

    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .alpha(if (status == PresetStatus.NotInstalled) 0.55f else 1f)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(p.packageName, 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    StatusChip(status)
                }
                Spacer(Modifier.height(2.dp))
                if (p.note.isNotBlank()) {
                    Text(
                        p.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "锁 ${p.activityName.substringAfterLast('.')}" +
                        (p.redirectUri?.let { "  ↗ $it" } ?: "  · 纯拦截"),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            when (status) {
                PresetStatus.Active -> Text(
                    "已启用",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PresetStatus.NotInstalled -> {}
                else -> TextButton(onClick = onApply, enabled = !busy) {
                    Text(
                        when {
                            busy -> "配置中…"
                            status == PresetStatus.NeedsRestore -> "恢复"
                            else -> "导入"
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            if (p.redirectUri != null && status != PresetStatus.NotInstalled && onPin != null) {
                TextButton(onClick = onPin, enabled = !busy) {
                    Text("直达", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (onEdit != null && onDelete != null) {
                IconButton(onClick = { onEdit(p) }) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.width(20.dp))
                }
                IconButton(onClick = { onDelete(p) }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.width(20.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: PresetStatus) {
    val (text, container, content) = when (status) {
        PresetStatus.Active -> Triple(
            "已启用", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer
        )
        PresetStatus.NeedsRestore -> Triple(
            "待恢复", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer
        )
        PresetStatus.NotImported -> Triple(
            "未导入", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant
        )
        PresetStatus.NotInstalled -> Triple(
            "未安装", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Surface(shape = MaterialTheme.shapes.extraSmall, color = container) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** 新建 / 编辑用户方案。 */
@Composable
private fun PresetEditorDialog(
    initial: ConfigPreset?,
    onSave: (ConfigPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var pkg by remember { mutableStateOf(initial?.packageName ?: "") }
    var activity by remember { mutableStateOf(initial?.activityName ?: "") }
    var redirect by remember { mutableStateOf(initial?.redirectUri ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    val valid = pkg.isNotBlank() && activity.isNotBlank() && label.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建方案" else "编辑方案") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pkg, onValueChange = { pkg = it.trim() },
                    label = { Text("包名") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = activity, onValueChange = { activity = it.trim() },
                    label = { Text("要锁定的界面（完整类名）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = redirect, onValueChange = { redirect = it },
                    label = { Text("拦截后跳转深链（留空则纯拦截）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("备注") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    ConfigPreset(
                        id = initial?.id ?: "",
                        label = label.trim(),
                        packageName = pkg.trim(),
                        activityName = activity.trim(),
                        redirectUri = redirect.trim().takeIf { it.isNotBlank() },
                        note = note.trim(),
                        verified = initial?.verified ?: false,
                        builtin = false,
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
