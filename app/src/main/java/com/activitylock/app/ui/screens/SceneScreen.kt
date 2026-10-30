package com.activitylock.app.ui.screens

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.Scene
import com.activitylock.app.core.SceneEngine
import com.activitylock.app.core.VerifyGate
import com.activitylock.app.ui.components.UnlockVerifyDialog
import kotlinx.coroutines.launch

/**
 * 场景模式：把方案库里的方案组成命名集合，一键启用/停用。
 * 启用 = 成员方案逐个应用（锁定+跳转）；停用 = 成员 App 全部解锁（需过最强解锁条件）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scenes = AppStore.scenes
    var editing by remember { mutableStateOf<Scene?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDeactivate by remember { mutableStateOf<Scene?>(null) }
    var busy by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("场景模式", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Default.Add, contentDescription = "新建场景")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "启用后，场景里的 App 全部上锁；停用后全部解锁。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (scenes.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("还没有场景", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点右上角「＋」新建，例如「工作模式」勾选小红书、抖音、B站。\n启用后这些 App 全部上锁，停用后临时放开。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(scenes, key = { it.id }) { scene ->
                        val memberCount = scene.presetIds.size
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        scene.label.ifBlank { "未命名场景" },
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        "$memberCount 个应用，${if (scene.active) "已启用" else "未启用"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = scene.active,
                                    enabled = !busy,
                                    onCheckedChange = { want ->
                                        if (want) {
                                            busy = true
                                            scope.launch {
                                                val n = SceneEngine.activate(scene)
                                                AppStore.upsertScene(scene.copy(active = true))
                                                AppStore.toast("「${scene.label.ifBlank { "场景" }}」已启用 $n 个应用")
                                                busy = false
                                            }
                                        } else {
                                            val req = SceneEngine.deactivateRequirement(context, scene)
                                            if (req.isNone) {
                                                busy = true
                                                scope.launch {
                                                    val n = SceneEngine.deactivate(scene)
                                                    AppStore.upsertScene(scene.copy(active = false))
                                                    AppStore.toast("场景已停用，$n 个界面解锁")
                                                    busy = false
                                                }
                                            } else {
                                                pendingDeactivate = scene
                                            }
                                        }
                                    }
                                )
                                IconButton(onClick = { editing = scene }) {
                                    androidx.compose.material3.Icon(
                                        androidx.compose.material.icons.Icons.Default.Edit,
                                        contentDescription = "编辑"
                                    )
                                }
                                IconButton(onClick = { AppStore.removeScene(scene.id) }) {
                                    androidx.compose.material3.Icon(
                                        androidx.compose.material.icons.Icons.Default.Delete,
                                        contentDescription = "删除"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDeactivate?.let { scene ->
        UnlockVerifyDialog(
            requirement = SceneEngine.deactivateRequirement(context, scene),
            title = "停用场景验证",
            hint = "停用将解锁 ${scene.presetIds.size} 个方案的所有界面",
            onConfirmed = {
                pendingDeactivate = null
                busy = true
                scope.launch {
                    val n = SceneEngine.deactivate(scene)
                    AppStore.upsertScene(scene.copy(active = false))
                    AppStore.toast("场景已停用，$n 个界面解锁")
                    busy = false
                }
            },
            onDismiss = { pendingDeactivate = null }
        )
    }

    if (creating || editing != null) {
        SceneEditorDialog(
            initial = editing,
            onSave = {
                AppStore.upsertScene(it)
                creating = false
                editing = null
            },
            onDismiss = { creating = false; editing = null }
        )
    }
}

@Composable
private fun SceneEditorDialog(
    initial: Scene?,
    onSave: (Scene) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    val allPresets = com.activitylock.app.core.BuiltinPresets.all + AppStore.userPresets
    var selected by remember {
        mutableStateOf(initial?.presetIds?.toSet() ?: emptySet())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建场景" else "编辑场景") },
        text = {
            // ★ 名称输入框会唤起键盘 → 让 Dialog window 随键盘收缩
            com.activitylock.app.ui.components.DialogImePan()
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("场景名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (allPresets.isEmpty()) {
                    Text("方案库为空：先去方案库导入或新建方案。")
                }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.height(280.dp)) {
                    items(allPresets, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = p.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + p.id else selected - p.id
                                }
                            )
                            Column {
                                Text(p.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    p.packageName.substringAfterLast('.'),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && selected.isNotEmpty(),
                onClick = {
                    onSave(
                        Scene(
                            id = initial?.id ?: "",
                            label = label.trim(),
                            presetIds = selected.toList(),
                            active = initial?.active ?: false,
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
