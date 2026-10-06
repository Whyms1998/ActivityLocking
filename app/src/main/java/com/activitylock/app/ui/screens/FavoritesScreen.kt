package com.activitylock.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Shapes
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AmsController
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.BlockStats
import com.activitylock.app.core.ConfigPreset
import com.activitylock.app.core.LockEngine
import com.activitylock.app.core.LockOutcome
import com.activitylock.app.core.LockRule
import com.activitylock.app.core.FocusEngine
import com.activitylock.app.core.FocusState
import com.activitylock.app.core.Scene
import com.activitylock.app.core.SceneEngine
import com.activitylock.app.core.UnlockExemptions
import com.activitylock.app.core.VerifyGate
import com.activitylock.app.ui.components.AppIcon
import com.activitylock.app.ui.components.UnlockVerifyDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            Text(
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

/**
 * 工作台（原收藏夹）：状态仪表 + 场景一键切换 + 搜索直达 + 规则分组列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onOpenApps: () -> Unit = {},
    onOpenPresets: () -> Unit = {},
    onOpenSchedules: () -> Unit = {},
    onOpenQuota: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenFocus: () -> Unit = {},
) {
    val rules = AppStore.rules
    val actual = remember { mutableStateMapOf<String, Boolean>() }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<LockRule?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    var deleting by remember { mutableStateOf<LockRule?>(null) }
    var pendingUnlock by remember { mutableStateOf<LockRule?>(null) }
    var pendingDelete by remember { mutableStateOf<LockRule?>(null) }
    var pendingSceneDeactivate by remember { mutableStateOf<Scene?>(null) }
    var gridDetailPkg by remember { mutableStateOf<String?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var sceneBusy by remember { mutableStateOf(false) }
    var todayBlocked by remember { mutableStateOf<Int?>(null) }
    var lastFocusCfg by remember { mutableStateOf<com.activitylock.app.core.FocusState.LastConfig?>(null) }
    var lastEarlyEnd by remember { mutableStateOf(0L) }
    var focusStarting by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTick) {
        lastFocusCfg = FocusState.lastConfig(ctx)
        lastEarlyEnd = FocusState.lastEarlyEnd(ctx)
    }

    fun startLastFocus() {
        val cfg = lastFocusCfg ?: return
        focusStarting = true
        scope.launch {
            val err = FocusEngine.start(ctx, cfg.mode, cfg.members.toSet(), cfg.durationMin, cfg.strict)
            AppStore.toast(if (err == null) "专注开始，共 " + cfg.durationMin + " 分钟" else err ?: "")
            refreshTick++
            focusStarting = false
        }
    }
    val uiPrefs = ctx.getSharedPreferences("ui_state", Context.MODE_PRIVATE)
    var gridView by remember { mutableStateOf(uiPrefs.getBoolean("workbench_grid", true)) }

    var focusActive by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTick) {
        todayBlocked = withContext(Dispatchers.IO) {
            runCatching { BlockStats.read()?.today }.getOrNull()
        }
        FocusState.load(ctx)?.let { focusActive = true } ?: run { focusActive = false }
        lastFocusCfg = FocusState.lastConfig(ctx)
        lastEarlyEnd = FocusState.lastEarlyEnd(ctx)
    }

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

    fun ruleToggle(rule: LockRule, want: Boolean) {
        val ruleKey = key(rule)
        if (want) {
            busy[ruleKey] = true
            scope.launch {
                onOutcome(LockEngine.apply(rule.packageName, rule.activityName, true, rule.redirectUri))
                refresh()
                busy[ruleKey] = false
            }
        } else if (!VerifyGate.requirementFor(ctx, rule).isNone) {
            pendingUnlock = rule
        } else {
            busy[ruleKey] = true
            scope.launch {
                onOutcome(LockEngine.apply(rule.packageName, rule.activityName, false))
                refresh()
                busy[ruleKey] = false
            }
        }
    }

    fun ruleDeleteRequest(rule: LockRule) {
        val locked = if (rule.block) rule.locked
        else (actual[key(rule)] ?: false)
        if (locked && !VerifyGate.requirementFor(ctx, rule).isNone) {
            pendingDelete = rule
        } else {
            deleting = rule
        }
    }

    fun toggleScene(scene: Scene) {
        if (sceneBusy) return
        if (!scene.active) {
            sceneBusy = true
            scope.launch {
                val n = SceneEngine.activate(scene)
                AppStore.upsertScene(scene.copy(active = true))
                AppStore.toast("「${scene.label.ifBlank { "场景" }}」已启用 $n 个应用")
                sceneBusy = false
            }
        } else {
            val req = SceneEngine.deactivateRequirement(ctx, scene)
            if (req.isNone) {
                sceneBusy = true
                scope.launch {
                    val n = SceneEngine.deactivate(scene)
                    AppStore.upsertScene(scene.copy(active = false))
                    AppStore.toast("场景已停用，$n 个界面解锁")
                    sceneBusy = false
                }
            } else {
                pendingSceneDeactivate = scene
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("工作台", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                gridView = !gridView
                uiPrefs.edit().putBoolean("workbench_grid", gridView).apply()
            }) { Text(if (gridView) "列表" else "宫格") }
            IconButton(onClick = { refreshTick++ }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新状态")
            }
        }

        QuickPanel(
            lockedCount = rules.count { it.locked },
            todayBlocked = todayBlocked,
            scenes = AppStore.scenes,
            sceneBusy = sceneBusy,
            onToggleScene = { toggleScene(it) },
            onOpenPresets = onOpenPresets,
            onOpenSchedules = onOpenSchedules,
            onOpenQuota = onOpenQuota,
            onOpenStats = onOpenStats,
        )

        // 一键专注 + 破功回流
        val focusCfg = lastFocusCfg
        if (!focusActive && focusCfg != null) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    val earlyAgo = lastEarlyEnd.takeIf { it > 0 }
                        ?.let { ((System.currentTimeMillis() - it) / 3600_000L).toInt() }
                    if (earlyAgo != null && earlyAgo < 48) {
                        Text(
                            "上次专注提前结束了——没关系，重新开始一次？",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Button(
                        onClick = { startLastFocus() },
                        enabled = !focusStarting && !sceneBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (focusStarting) "启动中…"
                            else "▶ 开始专注 " + focusCfg.durationMin + " 分钟（上次配置）"
                        )
                    }
                }
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
                    "去「应用」页找到目标 App，选择要锁定的界面；也可以到「方案库」一键配置常用 App。\n锁定的界面会自动出现在这里。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (gridView) {
            val byPkg = remember(rules) {
                rules.groupBy { it.packageName }.mapValues { (_, v) ->
                    Pair(v.count { r -> r.locked }, v.size)
                }
            }
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(92.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                gridItems(byPkg.entries.sortedBy { it.key }, key = { it.key }) { (pkg, counts) ->
                    val (locked, total) = counts
                    val label = AppStore.findApp(pkg)?.label ?: pkg
                    Column(
                        Modifier
                            .padding(6.dp)
                            .clickable { gridDetailPkg = pkg }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AppIcon(pkg, 56.dp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val exemptMin = ((com.activitylock.app.core.UnlockExemptions.until(ctx) - System.currentTimeMillis()) / 60000L).toInt()
                        Text(
                            when {
                                exemptMin > 0 -> "🔓 临时解锁 " + exemptMin + "分"
                                locked == total -> "🔒 " + total
                                else -> "" + locked + "/" + total
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = when {
                                exemptMin > 0 -> MaterialTheme.colorScheme.tertiary
                                locked == total -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
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
                            expanded = expanded[pkg] == true,
                            onToggle = { expanded[pkg] = !(expanded[pkg] == true) }
                        )
                    }
                    if (expanded[pkg] != false) {
                        items(groupRules, key = { key(it) }) { rule ->
                            val ruleKey = key(rule)
                            // block 模式以规则里的 locked 为准；pm 模式以系统实际状态为准
                            val pmDisabled = !rule.block && (actual[ruleKey] ?: false)
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
                                                        busy[ruleKey] = true
                                                        scope.launch {
                                                            onOutcome(LockEngine.apply(rule.packageName, rule.activityName, true, rule.redirectUri))
                                                            refresh()
                                                            busy[ruleKey] = false
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
                                                    VerifyGate.METHOD_QUIZ -> "答题"
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
                                        enabled = busy[ruleKey] != true,
                                        onCheckedChange = { wantLock ->
                                            if (wantLock) {
                                                busy[ruleKey] = true
                                                scope.launch {
                                                    onOutcome(LockEngine.apply(rule.packageName, rule.activityName, true, rule.redirectUri))
                                                    refresh()
                                                    busy[ruleKey] = false
                                                }
                                            } else if (!VerifyGate.requirementFor(ctx, rule).isNone) {
                                                // 自律模式：按该界面的解锁条件验证
                                                pendingUnlock = rule
                                            } else {
                                                busy[ruleKey] = true
                                                scope.launch {
                                                    onOutcome(LockEngine.apply(rule.packageName, rule.activityName, false))
                                                    refresh()
                                                    busy[ruleKey] = false
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                item(key = "tail_add") {
                    TextButton(
                        onClick = onOpenApps,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    ) { Text("＋ 添加要锁定的界面") }
                }
            }
        }
    }

    gridDetailPkg?.let { pkg ->
        GridDetailDialog(
            pkg = pkg,
            onClose = { gridDetailPkg = null },
            onEdit = { editing = it },
            onDeleteRequest = { ruleDeleteRequest(it) },
            onToggle = { rule, want -> ruleToggle(rule, want) },
            busy = busy,
        )
    }

    pendingSceneDeactivate?.let { scene ->
        UnlockVerifyDialog(
            requirement = SceneEngine.deactivateRequirement(ctx, scene),
            title = "停用场景验证",
            hint = "停用将解锁该场景的所有界面",
            onConfirmed = {
                pendingSceneDeactivate = null
                sceneBusy = true
                scope.launch {
                    val n = SceneEngine.deactivate(scene)
                    AppStore.upsertScene(scene.copy(active = false))
                    AppStore.toast("场景已停用，$n 个界面解锁")
                    sceneBusy = false
                }
            },
            onDismiss = { pendingSceneDeactivate = null }
        )
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
        var vQuizCount by remember(rule) {
            mutableStateOf((rule.quizCount ?: VerifyGate.quizCount(ctx)).toString())
        }
        var vRandomDigits by remember(rule) {
            mutableStateOf(VerifyGate.randomDigits(ctx).toString())
        }
        var quota by remember(rule) { mutableStateOf(rule.quotaMin?.toString() ?: "") }
        var pendingWeakSave by remember(rule) { mutableStateOf(false) }
        val globalReq = VerifyGate.globalRequirement(ctx)
        val globalDesc = when (globalReq.mode) {
            VerifyGate.MODE_NONE -> "已关闭"
            VerifyGate.METHOD_DELAY -> "等待${globalReq.delaySec}秒"
            VerifyGate.METHOD_QUIZ -> "答题"
            else -> "密码"
        }
        val curReq = VerifyGate.requirementFor(ctx, rule)
        // 修改后的有效解锁条件（复刻 requirementFor 的解析）
        val afterMode: String = when (vMode) {
            VerifyGate.MODE_NONE -> VerifyGate.MODE_NONE
            VerifyGate.METHOD_DELAY, VerifyGate.METHOD_PASSWORD, VerifyGate.METHOD_QUIZ ->
                vMode ?: VerifyGate.METHOD_DELAY
            else -> globalReq.mode
        }
        // 降低门槛（改弱条件/缩短等待）且界面处于锁定中 → 保存前需过当前验证
        val weakening = rule.locked && (
            VerifyGate.strength(afterMode) < VerifyGate.strength(curReq.mode) ||
                (afterMode == VerifyGate.METHOD_DELAY && curReq.mode == VerifyGate.METHOD_DELAY &&
                    (vDelay.toIntOrNull() ?: VerifyGate.delaySeconds(ctx)) < curReq.delaySec)
            )
        fun doSave() {
            AppStore.updateNote(rule.packageName, rule.activityName, text)
            AppStore.updateRedirect(rule.packageName, rule.activityName, redirect)
            AppStore.updateVerify(
                rule.packageName, rule.activityName,
                vMode, vDelay.trim().toIntOrNull(), vPwd, vQuizCount.trim().toIntOrNull()
            )
            if (vMode == VerifyGate.METHOD_RANDOM) {
                VerifyGate.setRandomDigits(ctx, vRandomDigits.toIntOrNull() ?: 6)
            }
            AppStore.updateQuota(rule.packageName, rule.activityName, quota.trim().toIntOrNull())
            // 跳转目标/超额状态影响 Watcher 规则，立即同步；限额需要守护服务
            AmsController.writeRules()
            com.activitylock.app.core.QuotaService.startIfQuotaConfigured(ctx)
            editing = null
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
                    com.activitylock.app.ui.components.UnlockConditionEditor(
                        mode = vMode,
                        delaySec = vDelay.toIntOrNull() ?: VerifyGate.delaySeconds(ctx),
                        password = vPwd,
                        quizCount = vQuizCount.toIntOrNull() ?: VerifyGate.quizCount(ctx),
                        randomDigits = vRandomDigits.toIntOrNull() ?: 6,
                        globalDesc = "",
                        onModeChange = { vMode = it },
                        onDelayChange = { vDelay = it.toString() },
                        onPasswordChange = { vPwd = it },
                        onQuizCountChange = { vQuizCount = it.toString() },
                        onRandomDigitsChange = { vRandomDigits = it.toString() },
                    )
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
                    if (weakening) pendingWeakSave = true else doSave()
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            }
        )

        if (pendingWeakSave) {
            UnlockVerifyDialog(
                requirement = curReq,
                title = "降低门槛验证",
                hint = "本次修改会降低该界面的解锁门槛",
                onConfirmed = {
                    pendingWeakSave = false
                    doSave()
                },
                onDismiss = { pendingWeakSave = false }
            )
        }
    }
}

/**
 * 工作台快捷面板：状态行 + 场景一键切换 + 搜索直达（桌面快捷方式的 App 内镜像）+ 功能入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickPanel(
    lockedCount: Int,
    todayBlocked: Int?,
    scenes: List<Scene>,
    sceneBusy: Boolean,
    onToggleScene: (Scene) -> Unit,
    onOpenPresets: () -> Unit,
    onOpenSchedules: () -> Unit,
    onOpenQuota: () -> Unit,
    onOpenStats: () -> Unit,
) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(
            "已锁定 $lockedCount 个界面" + (todayBlocked?.let { " · 今日拦截 $it 次" } ?: ""),
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (scenes.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                items(scenes, key = { it.id }) { s ->
                    FilterChip(
                        selected = s.active,
                        onClick = { onToggleScene(s) },
                        label = { Text(s.label.ifBlank { "场景" }) }
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(onClick = onOpenPresets, modifier = Modifier.weight(1f), label = {
                Text("方案", maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            })
            AssistChip(onClick = onOpenSchedules, modifier = Modifier.weight(1f), label = {
                Text("计划", maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            })
            AssistChip(onClick = onOpenQuota, modifier = Modifier.weight(1f), label = {
                Text("限额", maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            })
            AssistChip(onClick = onOpenStats, modifier = Modifier.weight(1f), label = {
                Text("统计", maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            })
        }
        Spacer(Modifier.height(6.dp))
    }
}


/**
 * 宫格点击后的 App 规则弹窗：只展示该 App 的界面，可直接编辑/解锁/删除。
 */
@Composable
private fun GridDetailDialog(
    pkg: String,
    onClose: () -> Unit,
    onEdit: (LockRule) -> Unit,
    onDeleteRequest: (LockRule) -> Unit,
    onToggle: (LockRule, Boolean) -> Unit,
    busy: Map<String, Boolean>,
) {
    val ctx = LocalContext.current
    val memberRules = AppStore.rules.filter { it.packageName == pkg }
    val lockedCount = memberRules.count { it.locked }
    val label = AppStore.findApp(pkg)?.label ?: pkg.substringAfterLast('.')

    Dialog(onDismissRequest = onClose) {
        Card(shape = MaterialTheme.shapes.small) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(pkg, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "已拦截 $lockedCount/${memberRules.size} 个界面",
                            style = MaterialTheme.typography.labelSmall
                                .copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onClose) { Text("关闭") }
                }
                    Spacer(Modifier.height(8.dp))
                }
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    memberRules.forEach { rule ->
                        val locked = if (rule.block) rule.locked
                        else AppStore.isDisabled(rule.packageName, rule.activityName)
                        val ruleBusy = busy["${rule.packageName}/${rule.activityName}"] == true
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    rule.note.ifBlank { rule.activityName.substringAfterLast('.') },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    rule.activityName.substringAfterLast('.') +
                                        if (locked) " · 拦截中" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onEdit(rule) }, enabled = !ruleBusy) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "编辑",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(onClick = { onDeleteRequest(rule) }, enabled = !ruleBusy) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Switch(
                                checked = locked,
                                enabled = !ruleBusy,
                                onCheckedChange = { want -> onToggle(rule, want) }
                            )
                        }
                    }
                }
            }
        }
    }
