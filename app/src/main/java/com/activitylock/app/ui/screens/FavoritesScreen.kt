package com.activitylock.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AmsController
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.AutoRelock
import com.activitylock.app.core.AutoRelockStore
import com.activitylock.app.core.AutoRelockService
import com.activitylock.app.core.ActivityRelockCfg
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
import com.activitylock.app.ui.components.DurationField
import com.activitylock.app.ui.components.DurationUnit
import com.activitylock.app.ui.components.SceneRowSpec
import com.activitylock.app.ui.components.SceneRowsCard
import com.activitylock.app.ui.components.SceneValueDialog
import com.activitylock.app.ui.components.SceneValueStyle
import com.activitylock.app.ui.components.UnlockVerifyDialog
import com.activitylock.app.ui.components.requirementDesc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun refresh(ctx: android.content.Context) {
    com.activitylock.app.core.AmsController.writeRules()
    com.activitylock.app.core.QuotaService.startIfQuotaConfigured(ctx)
}



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
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
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
                    "$count 个界面，已锁定 $lockedCount 个",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // ★ 「收起/展开」文字与箭头是同一个状态的两种表达，只留箭头。
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 0f else 180f)
            )
        }
    }
}

/**
 * 工作台（原收藏夹）：状态仪表 + 场景一键切换 + 规则分组列表。
 *
 * ★ v1.20.0 入口去重：删掉了 onOpenPresets / onOpenSchedules / onOpenQuota /
 *   onOpenStats / onOpenAutoRelock 一整批跳转参数——方案库、定时计划、时长限额、
 *   自动锁闭在「设置」页里全都有入口，工作台不再开第二条路径。
 * ★ v1.23.0：新增 [onOpenActivityRelock]，用于「应用弹窗 → 界面行 → 界面详情页」
 *   这条**唯一**的高级路径（文档 §14.4/§14.5）。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FavoritesScreen(
    onOpenApps: () -> Unit = {},
) {
    val rules = AppStore.rules
    val actual = remember { mutableStateMapOf<String, Boolean>() }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<LockRule?>(null) }
    /** 正在编辑解锁条件的规则（独立弹窗，入口常驻在卡片上）。 */
    var editingUnlock by remember { mutableStateOf<LockRule?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    var deleting by remember { mutableStateOf<LockRule?>(null) }
    var pendingUnlock by remember { mutableStateOf<LockRule?>(null) }
    var pendingDelete by remember { mutableStateOf<LockRule?>(null) }
    /** 正在配置自动锁闭（重锁延迟）的界面。 */
    var relockCfgRule by remember { mutableStateOf<LockRule?>(null) }
    /** 界面级自动锁闭削弱修改的验证闸门。 */
    var relockCfgVerify by remember { mutableStateOf<(() -> Unit)?>(null) }

    // ---- 分组 / 搜索 / 多选 / 批量（借鉴雹的首页交互） ----
    /** null = 全部；"" = 默认（未分组）；其他 = 分组 id。 */
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQ by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    val selectedPkgs = remember { mutableStateMapOf<String, Boolean>() }
    var menuOpen by remember { mutableStateOf(false) }
    var groupManage by remember { mutableStateOf<String?>(null) }
    var showNewGroupFor by remember { mutableStateOf<String?>(null) }
    var newGroupName by remember { mutableStateOf("") }
    var pendingBulkUnlock by remember { mutableStateOf<(() -> Unit)?>(null) }
    /** 分组管理对话框（新建 / 重命名 / 删除）。 */
    var groupsManageOpen by remember { mutableStateOf(false) }

    val shownRules = remember(rules, selectedGroup, AppStore.groupAssign, searchQ) {
        val base = when (selectedGroup) {
            null -> rules
            "" -> rules.filter { AppStore.groupAssign[it.packageName] == null }
            else -> rules.filter { AppStore.groupAssign[it.packageName] == selectedGroup }
        }
        if (searchQ.isBlank()) base
        else base.filter {
            val lb = AppStore.findApp(it.packageName)?.label ?: it.packageName
            lb.contains(searchQ, true) || it.packageName.contains(searchQ, true) ||
                it.note.contains(searchQ, true) || it.activityName.contains(searchQ, true)
        }
    }
    val visiblePkgs = shownRules.map { it.packageName }.distinct()
    val selectedList = selectedPkgs.filterValues { it }.keys.toList()

    var pendingSceneDeactivate by remember { mutableStateOf<Scene?>(null) }
    var gridDetailPkg by remember { mutableStateOf<String?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var sceneBusy by remember { mutableStateOf(false) }
    var todayBlocked by remember { mutableStateOf<Int?>(null) }
    var lastFocusCfg by remember { mutableStateOf<com.activitylock.app.core.FocusState.LastConfig?>(null) }
    var lastEarlyEnd by remember { mutableStateOf(0L) }
    var focusStarting by remember { mutableStateOf(false) }

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

    fun refreshPmState() {
        for (r in rules) {
            if (!r.block) {
                actual["${r.packageName}/${r.activityName}"] =
                    AppStore.isDisabled(r.packageName, r.activityName)
            }
        }
    }

    LaunchedEffect(rules, refreshTick) { refreshPmState() }

    fun bulkLock(pkgs: Collection<String>) {
        scope.launch {
            var n = 0
            for (rule in rules.toList()) {
                if (rule.packageName in pkgs && !rule.locked) {
                    val o = LockEngine.apply(rule.packageName, rule.activityName, true, rule.redirectUri)
                    if (o.locked) n++
                }
            }
            AutoRelockService.sync(ctx)
            AppStore.toast("已锁定 " + n + " 个界面")
            refreshPmState()
        }
    }



    fun bulkUnlockVerify(pkgs: Collection<String>) {
        val req = VerifyGate.bulkRequirement(ctx, AppStore.rules)
        val act: () -> Unit = {
            scope.launch {
                var n = 0
                for (rule in rules.toList()) {
                    if (rule.packageName in pkgs && rule.locked) {
                        val o = LockEngine.apply(rule.packageName, rule.activityName, false)
                        if (!o.locked) n++
                    }
                }
                AutoRelockService.sync(ctx)
                AppStore.toast("已解锁 " + n + " 个界面")
                refresh(ctx)
            }
        }
        if (req.isNone) act() else pendingBulkUnlock = act
    }

    /** FAB：有解锁态 → 一键冻结；全锁定 → 全部解锁（需验证）。 */
    fun freezeAllAction() {
        if (rules.any { !it.locked }) {
            scope.launch {
                var n = 0
                for (rule in rules.toList()) {
                    if (!rule.locked) {
                        val o = LockEngine.apply(rule.packageName, rule.activityName, true, rule.redirectUri)
                        if (o.locked) n++
                    }
                }
                AutoRelockService.sync(ctx)
                AppStore.toast("已全部锁定 " + n + " 个界面")
                refresh(ctx)
            }
        } else {
            val req = VerifyGate.bulkRequirement(ctx, AppStore.rules)
            val act: () -> Unit = {
                scope.launch {
                    var n = 0
                    for (rule in rules.toList()) {
                        if (rule.locked) {
                            val o = LockEngine.apply(rule.packageName, rule.activityName, false)
                            if (!o.locked) n++
                        }
                    }
                    AutoRelockService.sync(ctx)
                    AppStore.toast("已全部解锁 " + n + " 个界面")
                    refresh(ctx)
                }
            }
            if (req.isNone) act() else pendingBulkUnlock = act
        }
    }


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
                AutoRelockService.sync(ctx)
                refreshPmState()
                busy[ruleKey] = false
            }
        } else if (!VerifyGate.requirementFor(ctx, rule).isNone) {
            pendingUnlock = rule
        } else {
            busy[ruleKey] = true
            scope.launch {
                onOutcome(LockEngine.apply(rule.packageName, rule.activityName, false))
                // ★★ v1.24.16：**解锁界面不再摘除自动锁闭**（此处原为
                //   `setActivityAllLinks(..., false)`，v1.23.6 引入）。
                //
                //   真机事故 2026-10-14：微博「应用级锁屏后亮着、界面级整行关着、息屏后一条都不锁」。
                //   根因就是这一行 —— 用户为了**试验自动锁闭**而先解锁微博，这一动作顺手把
                //   MainTabActivity 从自动锁闭里摘除了；界面级压过应用级（v1.24.4），
                //   于是「锁屏后自动锁回」永远不可能发生，而应用级开关还亮着 → 假开关。
                //
                //   语义修正：**「当前锁没锁」与「要不要参与自动锁闭」是两件事**。
                //   自动锁闭是一套**策略**（解锁后 / 退出后 / 锁屏后把界面重新锁上），
                //   它的全部意义就是「解锁之后还会自己锁回来」；若解锁即退出，这个功能自相矛盾。
                //   想「临时用一会儿不被锁回」请走**临时解锁**（限时豁免），那才是它该管的事。
                AutoRelockService.sync(ctx)
                refreshPmState()
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

    var fabPos by remember { mutableStateOf<Offset?>(null) }
    var fabSize by remember { mutableStateOf(android.util.Size(0, 0)) }
    var boxSize by remember { mutableStateOf(android.util.Size(0, 0)) }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = android.util.Size(it.width, it.height) }
    ) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) {
                Text(
                    "已选择: " + selectedList.size,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    visiblePkgs.forEach { selectedPkgs[it] = true }
                }) { Text("全选") }
                TextButton(onClick = {
                    selectedPkgs.clear()
                    selecting = false
                }) { Text("退出") }
                var groupMenuOpen by remember { mutableStateOf(false) }
                TextButton(onClick = { groupMenuOpen = true }) { Text("分组") }
                Box {
                    DropdownMenu(expanded = groupMenuOpen, onDismissRequest = { groupMenuOpen = false }) {
                        AppStore.groups.forEach { g ->
                            DropdownMenuItem(
                                text = { Text("分组到：" + g.name) },
                                onClick = {
                                    selectedList.forEach { AppStore.assignGroup(it, g.id) }
                                    groupMenuOpen = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("移出分组") },
                            onClick = {
                                selectedList.forEach { AppStore.assignGroup(it, null) }
                                groupMenuOpen = false
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("＋ 新建分组") },
                            onClick = {
                                showNewGroupFor = ""
                                groupMenuOpen = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("管理分组…") },
                            onClick = {
                                groupsManageOpen = true
                                groupMenuOpen = false
                            }
                        )
                    }
                }
            } else {
                Text("工作台", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) searchQ = "" }) {
                    Icon(Icons.Default.Search, contentDescription = "搜索")
                }
                IconButton(onClick = {
                    selecting = !selecting
                    selectedPkgs.clear()
                }) {
                    Icon(Icons.Default.Checklist, contentDescription = "多选")
                }
                IconButton(onClick = {
                    gridView = !gridView
                    uiPrefs.edit().putBoolean("workbench_grid", gridView).apply()
                }) {
                    Icon(
                        if (gridView) Icons.Default.ViewList else Icons.Default.GridView,
                        contentDescription = if (gridView) "切换为列表" else "切换为宫格"
                    )
                }
                IconButton(onClick = { refreshTick++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新状态")
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("锁定可见") },
                            onClick = {
                                menuOpen = false
                                bulkLock(if (selectedList.isNotEmpty()) selectedList else visiblePkgs)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("解锁可见") },
                            onClick = {
                                menuOpen = false
                                bulkUnlockVerify(if (selectedList.isNotEmpty()) selectedList else visiblePkgs)
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("全部锁定") },
                            onClick = { menuOpen = false; bulkLock(rules.map { it.packageName }) }
                        )
                        DropdownMenuItem(
                            text = { Text("全部解锁") },
                            onClick = { menuOpen = false; bulkUnlockVerify(rules.map { it.packageName }) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("锁定默认分组") },
                            onClick = {
                                menuOpen = false
                                bulkLock(rules.filter { AppStore.groupAssign[it.packageName] == null }.map { it.packageName })
                            }
                        )
                    }
                }
            }
        }

        if (searchOpen && !selecting) {
            OutlinedTextField(
                value = searchQ,
                onValueChange = { searchQ = it },
                placeholder = { Text("搜索 App 名称 / 包名 / 备注") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { searchOpen = false; searchQ = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭搜索")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(6.dp))
        }

        QuickPanel(
            lockedCount = rules.count { it.locked },
            todayBlocked = todayBlocked,
            scenes = AppStore.scenes,
            sceneBusy = sceneBusy,
            onToggleScene = { toggleScene(it) },
        )

        // ---- 应用分组标签（借鉴雹）：全部 / 默认 / 自定义；长按管理 ----
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = selectedGroup == null,
                onClick = { selectedGroup = null },
                label = { Text("全部") }
            )
            FilterChip(
                selected = selectedGroup == "",
                onClick = { selectedGroup = "" },
                label = { Text("默认") }
            )
            AppStore.groups.forEach { g ->
                FilterChip(
                    selected = selectedGroup == g.id,
                    onClick = { selectedGroup = g.id },
                    label = { Text(g.name) },
                    modifier = Modifier.combinedClickable(
                        onClick = { selectedGroup = g.id },
                        onLongClick = { groupManage = g.id }
                    )
                )
            }
            FilterChip(
                selected = false,
                onClick = { groupsManageOpen = true },
                label = { Text("＋ 管理") }
            )
        }

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
            val byPkg = remember(shownRules) {
                shownRules.groupBy { it.packageName }.mapValues { (_, v) ->
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
                            .clickable {
                                if (selecting) selectedPkgs[pkg] = !(selectedPkgs[pkg] ?: false)
                                else gridDetailPkg = pkg
                            }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (selecting) {
                            Checkbox(
                                checked = selectedPkgs[pkg] == true,
                                onCheckedChange = { selectedPkgs[pkg] = it }
                            )
                        }
                        AppIcon(pkg, 56.dp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val exemptMin = ((UnlockExemptions.until(ctx) - System.currentTimeMillis()) / 60000L).toInt()
                        // ★ 统一成「图标 + 数字」一种语言：🔒 有锁定，🔓 没锁定。
                        Text(
                            when {
                                exemptMin > 0 -> "🔓 临时解锁 " + exemptMin + "分"
                                locked == total -> "🔒 " + total
                                locked == 0 -> "🔓 未锁"
                                else -> "🔒 " + locked + "/" + total
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = when {
                                exemptMin > 0 -> MaterialTheme.colorScheme.tertiary
                                locked > 0 -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        } else {
            val grouped = remember(shownRules) {
                shownRules.sortedBy { it.createdAt }.groupBy { it.packageName }
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
                                        Spacer(Modifier.height(3.dp))
                                        // 解锁条件：常驻显示（含「跟随全局」）+ 可点击直达编辑。
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.clickable { editingUnlock = rule }
                                        ) {
                                            Text(
                                                "🔓 解锁 " + requirementDesc(VerifyGate.requirementFor(ctx, rule)) + "  ▸",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                    IconButton(onClick = { editing = rule }) {
                                        Icon(Icons.Default.Edit, contentDescription = "备注")
                                    }
                                    IconButton(onClick = { ruleDeleteRequest(rule) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "删除条目")
                                    }
                                    Switch(
                                        checked = locked,
                                        enabled = busy[ruleKey] != true,
                                        onCheckedChange = { wantLock -> ruleToggle(rule, wantLock) }
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

    // 雪花 FAB：默认右下角，可拖动（位置会话内记忆）
    androidx.compose.material3.FloatingActionButton(
        onClick = { freezeAllAction() },
        containerColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset {
                val base = Offset(
                    (boxSize.width - fabSize.width - 48.dp.roundToPx()).toFloat(),
                    (boxSize.height - fabSize.height - 120.dp.roundToPx()).toFloat(),
                )
                val p0 = fabPos ?: base
                IntOffset(
                    p0.x.coerceIn(0f, (boxSize.width - fabSize.width).toFloat()).roundToInt(),
                    p0.y.coerceIn(0f, (boxSize.height - fabSize.height).toFloat()).roundToInt(),
                )
            }
            .onSizeChanged { fabSize = android.util.Size(it.width, it.height) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val base = Offset(
                        (boxSize.width - fabSize.width - 48.dp.roundToPx()).toFloat(),
                        (boxSize.height - fabSize.height - 120.dp.roundToPx()).toFloat(),
                    )
                    val cur = fabPos ?: base
                    fabPos = Offset(
                        (cur.x + dragAmount.x).coerceIn(0f, (boxSize.width - fabSize.width).toFloat()),
                        (cur.y + dragAmount.y).coerceIn(0f, (boxSize.height - fabSize.height).toFloat()),
                    )
                }
            }
    ) {
        Icon(
            Icons.Default.AcUnit,
            contentDescription = if (rules.any { !it.locked }) "一键冻结" else "全部解锁",
            tint = MaterialTheme.colorScheme.onPrimary
        )
    }
    }

    gridDetailPkg?.let { pkg ->
        GridDetailDialog(
            pkg = pkg,
            onClose = { gridDetailPkg = null },
            onToggleLock = { cls, want ->
                AppStore.rules.firstOrNull {
                    it.packageName == pkg && it.activityName == cls
                }?.let { ruleToggle(it, want) }
            },
            onEdit = { editing = it },
            onEditUnlock = { editingUnlock = it },
            onDeleteRequest = { ruleDeleteRequest(it) },
            onRelockConfig = { relockCfgRule = it },
        )
    }

    // ---------- 界面级自动锁闭配置（界面级显式设置 > 全局） ----------
    // ---------- 界面级自动锁闭（旧版场景行样式：开关 + 延迟值，点值弹层编辑） ----------
    relockCfgRule?.let { rule ->
        val cfgInit = AutoRelock.activityCfg(rule.packageName, rule.activityName)
        var unlockP by remember { mutableStateOf(cfgInit?.unlockParticipate ?: true) }
        var unlockD by remember { mutableStateOf(cfgInit?.unlockDelaySec ?: -1) }
        var exitP by remember { mutableStateOf(cfgInit?.exitParticipate ?: true) }
        var exitD by remember { mutableStateOf(cfgInit?.exitDelaySec ?: -1) }
        var screenOffP by remember { mutableStateOf(cfgInit?.screenOffParticipate ?: true) }
        var screenOffD by remember { mutableStateOf(cfgInit?.screenOffDelaySec ?: -1) }
        var valueLink by remember { mutableStateOf<Int?>(null) }
        var pendingApply by remember { mutableStateOf<(() -> Unit)?>(null) }
        val label = AppStore.findApp(rule.packageName)?.label
            ?: rule.activityName.substringAfterLast('.')

        fun globalFor(link: Int): Int = when (link) {
            AutoRelockStore.LINK_EXIT -> AutoRelock.globalExitDelaySec
            AutoRelockStore.LINK_SCREEN_OFF -> AutoRelock.globalScreenOffDelaySec
            else -> AutoRelock.globalDelaySec
        }

        fun applyCfg() {
            AutoRelock.setActivityCfg(
                ActivityRelockCfg(
                    pkg = rule.packageName,
                    cls = rule.activityName,
                    unlockParticipate = unlockP, unlockDelaySec = unlockD,
                    exitParticipate = exitP, exitDelaySec = exitD,
                    screenOffParticipate = screenOffP, screenOffDelaySec = screenOffD,
                )
            )
            AutoRelockService.sync(ctx)
            AppStore.toast("已保存「" + label + "」的自动锁闭")
        }

        AlertDialog(
            onDismissRequest = { relockCfgRule = null },
            title = { Text("自动锁闭 · " + label) },
            text = {
                Column {
                    Text(
                        "本界面的重锁时机与延迟；界面级设置优先于全局，全局总闸关闭时一律不锁。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    SceneRowsCard(
                        rows = listOf(
                            SceneRowSpec(
                                name = "解锁后",
                                valueText = if (unlockD < 0) "跟随全局" else relockDelayLabel(unlockD),
                                style = if (unlockD < 0) SceneValueStyle.INHERIT else SceneValueStyle.CUSTOM,
                                checked = unlockP && AutoRelock.relockAfterUnlockEnabled,
                                onValueClick = { valueLink = AutoRelockStore.LINK_UNLOCK },
                                onSwitchChange = { on ->
                                    val apply = { unlockP = on; applyCfg() }
                                    if (on) apply() else pendingApply = apply
                                }
                            ),
                            SceneRowSpec(
                                name = "退出后",
                                valueText = if (exitD < 0) "跟随全局" else relockDelayLabel(exitD),
                                style = if (exitD < 0) SceneValueStyle.INHERIT else SceneValueStyle.CUSTOM,
                                checked = exitP && AutoRelock.relockOnExitEnabled,
                                onValueClick = { valueLink = AutoRelockStore.LINK_EXIT },
                                onSwitchChange = { on ->
                                    val apply = { exitP = on; applyCfg() }
                                    if (on) apply() else pendingApply = apply
                                }
                            ),
                            SceneRowSpec(
                                name = "锁屏后",
                                valueText = if (screenOffD < 0) "跟随全局" else relockDelayLabel(screenOffD),
                                style = if (screenOffD < 0) SceneValueStyle.INHERIT else SceneValueStyle.CUSTOM,
                                checked = screenOffP && AutoRelock.relockOnScreenOffEnabled,
                                onValueClick = { valueLink = AutoRelockStore.LINK_SCREEN_OFF },
                                onSwitchChange = { on ->
                                    val apply = { screenOffP = on; applyCfg() }
                                    if (on) apply() else pendingApply = apply
                                }
                            ),
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点时间可改（跟随全局 / 自定义秒分时）；开关关闭 = 该界面此时机不自动重锁。" +
                            "调大延迟或关闭开关属于削弱，需先通过验证。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { relockCfgRule = null }) { Text("完成") }
            }
        )

        valueLink?.let { link ->
            val global = globalFor(link)
            val (initP, initD) = when (link) {
                AutoRelockStore.LINK_EXIT -> exitP to exitD
                AutoRelockStore.LINK_SCREEN_OFF -> screenOffP to screenOffD
                else -> unlockP to unlockD
            }
            val oldEff = if (initP) (if (initD < 0) global else initD) else 0
            val sceneName = when (link) {
                AutoRelockStore.LINK_EXIT -> "退出后"
                AutoRelockStore.LINK_SCREEN_OFF -> "锁屏后"
                else -> "解锁后"
            }
            SceneValueDialog(
                sceneName = sceneName,
                allowFollow = true,
                parentName = "全局",
                parentSec = global,
                initFollow = initD < 0,
                initSec = if (initD < 0) global else initD,
                onDismiss = { valueLink = null },
                onConfirm = { follow, sec ->
                    valueLink = null
                    val newEff = if (follow) global else sec
                    val apply = {
                        when (link) {
                            AutoRelockStore.LINK_EXIT -> { exitP = true; exitD = if (follow) -1 else sec }
                            AutoRelockStore.LINK_SCREEN_OFF -> { screenOffP = true; screenOffD = if (follow) -1 else sec }
                            else -> { unlockP = true; unlockD = if (follow) -1 else sec }
                        }
                        applyCfg()
                    }
                    // 调大延迟 = 削弱 → 验证
                    if (newEff > oldEff) pendingApply = apply else apply()
                }
            )
        }

        pendingApply?.let { action ->
            com.activitylock.app.ui.components.UnlockVerifyDialog(
                requirement = VerifyGate.bulkRequirement(ctx, AppStore.rules),
                title = "修改自动锁闭",
                hint = "削弱自律约束的修改需要先通过验证",
                enforceWindow = false,
                onConfirmed = {
                    pendingApply = null
                    action()
                },
                onDismiss = { pendingApply = null }
            )
        }
    }

    // ---------- 分组管理：新建 + 逐组重命名 / 删除 ----------
    if (groupsManageOpen) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { groupsManageOpen = false },
            title = { Text("管理分组") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it.take(12) },
                            label = { Text("新建分组名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            enabled = newName.isNotBlank(),
                            onClick = {
                                AppStore.addGroup(newName)
                                newName = ""
                            }
                        ) { Text("创建") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (AppStore.groups.isEmpty()) {
                            Text(
                                "还没有自定义分组",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AppStore.groups.forEach { g ->
                            var nameEdit by remember(g.id) { mutableStateOf(g.name) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = nameEdit,
                                    onValueChange = { nameEdit = it.take(12) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    enabled = nameEdit.isNotBlank() && nameEdit != g.name,
                                    onClick = { AppStore.renameGroup(g.id, nameEdit) }
                                ) { Text("重命名") }
                                IconButton(onClick = { AppStore.removeGroup(g.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除分组",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { groupsManageOpen = false }) { Text("完成") }
            }
        )
    }

    pendingBulkUnlock?.let { action ->
        com.activitylock.app.ui.components.UnlockVerifyDialog(
            requirement = VerifyGate.bulkRequirement(ctx, AppStore.rules),
            title = "批量解锁",
            hint = "解锁属于削弱自律约束的操作，需要先通过验证",
            onConfirmed = {
                pendingBulkUnlock = null
                action()
            },
            onDismiss = { pendingBulkUnlock = null }
        )
    }

    relockCfgVerify?.let { action ->
        com.activitylock.app.ui.components.UnlockVerifyDialog(
            requirement = VerifyGate.bulkRequirement(ctx, AppStore.rules),
            title = "修改自动锁闭",
            hint = "削弱自律约束的修改需要先通过验证",
            enforceWindow = false,
            onConfirmed = {
                relockCfgVerify = null
                action()
            },
            onDismiss = { relockCfgVerify = null }
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
                    // ★★ v1.24.16：验证通过后的解锁同样**不再摘除自动锁闭**（原为
                    //   `setActivityAllLinks(..., false)`）。理由见 `ruleToggle` 解锁分支的长注释：
                    //   「当前锁态」与「是否参与自动锁闭」解耦，否则「锁屏后自动锁回」永远不生效。
                    AutoRelockService.sync(ctx)
                    refreshPmState()
                    busy[key(rule)] = false
                }
            },
            onDismiss = { pendingUnlock = null }
        )
    }

    editing?.let { rule ->
        var text by remember(rule) { mutableStateOf(rule.note) }
        var redirect by remember(rule) { mutableStateOf(rule.redirectUri ?: "") }
        /** 应用跳转开关：关 = 仅拦截（redirect 存空）；开 = 显示目标输入。 */
        var redirectOn by remember(rule) { mutableStateOf(redirect.isNotBlank()) }
        // ★ v1.24.9：每日限额改用统一的时长输入（分钟 / 小时）；0 = 不限
        var quotaSec by remember(rule) { mutableStateOf((rule.quotaMin ?: 0) * 60) }
        val quotaOver = quotaSec / 60 > 1440
        // ★「弹不出输入法」的直接修复：打开即把焦点放到备注框并**主动唤起键盘**。
        val noteFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        LaunchedEffect(rule) {
            noteFocus.requestFocus()
            keyboard?.show()
        }
        fun doSave() {
            AppStore.updateNote(rule.packageName, rule.activityName, text)
            // 0 / 不足 1 分钟 = 不限额（对应 null）
            AppStore.updateQuota(
                rule.packageName, rule.activityName,
                if (quotaSec < 60) null else quotaSec / 60
            )
            // 跳转目标/超额状态影响 Watcher 规则，立即同步；限额需要守护服务
            com.activitylock.app.core.QuotaService.startIfQuotaConfigured(ctx)
            editing = null
            // ★ 走 LockEngine.saveRedirect：必要时把该条目切到「拦截模式 + 组件启用」，
            //   否则给一条 pm 模式的条目补填跳转只会静默无效（详见 LockEngine 注释）。
            scope.launch {
                val o = LockEngine.saveRedirect(rule.packageName, rule.activityName, redirect)
                if (o != null && !o.locked) AppStore.toast("跳转已保存，但启用拦截失败：${o.detail}")
                refreshPmState()
            }
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑界面") },
            text = {
                com.activitylock.app.ui.components.DialogImeResize()
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(rule.activityName, style = MaterialTheme.typography.bodySmall)
                    // ★ 跳转必须配在「桌面入口」上（2026-10-13 真机排查第二根因）：
                    //   点桌面图标会先进入 launcher Activity；若那个界面也被锁、且没配跳转，
                    //   请求在入口就被挡回桌面，更深界面上配的跳转**永远执行不到**。
                    //   实测抖音：跳转填在 MainActivity，而图标入口是 SplashActivity，
                    //   SplashActivity 恰好是空跳转的纯拦截 → 换多少个链接都不生效。
                    val entryCls = runCatching {
                        AppStore.findApp(rule.packageName)?.activities
                            ?.firstOrNull { it.isLauncher }?.cls
                    }.getOrNull()
                    if (entryCls != null && entryCls != rule.activityName && redirect.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "点桌面图标会先进入入口界面 ${entryCls.substringAfterLast('.')}；" +
                                    "如果那个界面也被锁定、却没有配跳转，请求会当场被挡回桌面，" +
                                    "这里的跳转就不会执行。想让「点图标直达」生效，请把跳转配在入口界面上。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("备注") },
                        placeholder = { Text("例如：小红书首页 / 抖音推荐流") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(noteFocus)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("应用跳转", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Switch(
                            checked = redirectOn,
                            onCheckedChange = { on ->
                                redirectOn = on
                                if (!on) redirect = ""
                            }
                        )
                    }
                    if (redirectOn) {
                        OutlinedTextField(
                            value = redirect,
                            onValueChange = { redirect = it },
                            label = { Text("跳转目标（拦截后跳到这里）") },
                        // ★ v1.24.7：跳转是自由文本，不是数字范围输入，此处只声明不做钳制；
                        //   真正需要范围的输入框一律用「原样保留 + isError + 常驻范围文案」口径。
                            placeholder = { Text("深链 xhsdiscover://search 或界面类名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            "仅拦截：界面被打开时直接挡回，不跳转",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // ★ v1.24.9：与自动锁闭详情页同构 —— 每日限额统一走 [DurationField]。
                    //   清空 = 0 = 不限额度；限额按分钟统计，所以单位只开放 分钟 / 小时，
                    //   保证「填多少就存多少」，不做任何取整。
                    Text("每日限额（留空不限）", style = MaterialTheme.typography.labelMedium)
                    DurationField(
                        seconds = quotaSec,
                        onChange = { quotaSec = it },
                        units = listOf(DurationUnit.MIN, DurationUnit.HOUR),
                        equivalent = { sec ->
                            when {
                                sec <= 0 -> "不限额"
                                sec / 60 > 1440 -> "超出上限：每天最多 24 小时"
                                else -> "每天可用 " + relockDelayLabel(sec)
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = { editingUnlock = rule },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "解锁条件：" + requirementDesc(VerifyGate.requirementFor(ctx, rule)),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        AppStore.upsertPreset(
                            ConfigPreset(
                                id = "",
                                label = text.ifBlank { rule.activityName.substringAfterLast('.') },
                                packageName = rule.packageName,
                                activityName = rule.activityName,
                                redirectUri = redirect.trim().takeIf { it.isNotBlank() },
                                note = text.trim(),
                                builtin = false,
                            )
                        )
                        AppStore.toast("已保存到「我的方案」")
                        editing = null
                    }) { Text("存为预设（可在方案库复用/导出）") }
                }
            },
            confirmButton = {
                // 超出「一天」的限额没有意义（RuleStore 也会兜底），所以超限时禁用保存；
                // 但用户在回显里能看到原因，不是"按钮莫名点不动"。
                TextButton(enabled = !quotaOver, onClick = { doSave() }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            }
        )
    }

    // ---------- 解锁条件独立弹窗（卡片 / 编辑对话框 / 自动锁闭页共用） ----------
    editingUnlock?.let { rule ->
        com.activitylock.app.ui.components.UnlockConditionDialog(
            rule = rule,
            onDismiss = { editingUnlock = null }
        )
    }
}

/**
 * 工作台顶部：状态行 + 场景一键切换。
 *
 * ★ v1.20.0 合并去重：原先这里还有一排「方案 / 计划 / 限额 / 锁闭」四个小 chip，
 *   与底部导航和「设置」页的入口完全重复（同一个页面两条路径），全部删除。
 * ★ 触控目标：场景按钮重做成 60dp 高的大块按钮，选中态用填充色 + 加粗边框表达。
 */
@Composable
private fun QuickPanel(
    lockedCount: Int,
    todayBlocked: Int?,
    scenes: List<Scene>,
    sceneBusy: Boolean,
    onToggleScene: (Scene) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "已锁定 $lockedCount 个界面",
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (todayBlocked != null) {
                // 左右两端对齐：靠留白分隔，不塞符号
                Spacer(Modifier.weight(1f))
                Text(
                    "今日拦截 $todayBlocked 次",
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = if (todayBlocked > 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (scenes.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 10.dp)
            ) {
                items(scenes, key = { it.id }) { s ->
                    SceneCard(s, sceneBusy, onToggleScene)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * 场景一键切换的大按钮（整块 60dp 高可点）。
 *
 * 状态一眼可辨（设计规范原则 1「关卡感」）：
 * - 启用中：primaryContainer 填充 + primary 2dp 边框 + 实心圆点；
 * - 未启用：surface 底 + outlineVariant 1dp 边框 + 空心圆点。
 */
@Composable
private fun SceneCard(s: Scene, busy: Boolean, onToggle: (Scene) -> Unit) {
    val active = s.active
    val contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = { onToggle(s) },
        enabled = !busy,
        shape = RoundedCornerShape(14.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            if (active) 2.dp else 1.dp,
            if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.height(60.dp)
    ) {
        Row(
            Modifier
                .fillMaxHeight()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .then(
                        if (active) Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                        else Modifier
                    )
                    .then(
                        if (!active) Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape)
                            .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        else Modifier
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    s.label.ifBlank { "场景" },
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    maxLines = 1
                )
                Text(
                    SceneEngine.memberPackages(s).size.toString() + " 个应用",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = if (active) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 宫格点击后的 App 详情弹窗（文档 §14.3 / §14.4）—— 本次重构的重点。
 *
 * ```
 * ┌──────────────────────────────────────┐
 * │ [图标] 微信              已锁定 2/2  ✕│
 * │  解锁后            5 分钟        [ ● ]│  ← 值蓝 = 自定义
 * │  退出后            10 秒         [ ● ]│  ← 值灰 = 跟随上级
 * │  锁屏后            立即          [ ● ]│
 * │  界面 · 2 个                      ▸  │  ← 默认折叠
 * │              [ 完 成 ]               │
 * └──────────────────────────────────────┘
 * ```
 *
 * ★ 相对旧版删掉的三样东西（文档 §14.3 规则 1/6、附录 B）：
 *   1. **「自动锁闭」总开关** —— 三个场景开关已能表达全部状态，总开关是第二个入口；
 *   2. **压缩三连行 `延迟 立即·立即·立即`** —— 读不出哪个是哪个，且没有逐场景开关；
 *   3. **界面行里的状态胶囊 + 「锁闭/解锁」按钮 + `⋮` 菜单 + 类名** ——
 *      全部收进「界面详情页」，行内只留「名称 + 开关」。
 */
@Composable
private fun GridDetailDialog(
    pkg: String,
    onClose: () -> Unit,
    /** 锁定 / 解锁某个界面（走与收藏页列表同一套 LockEngine 逻辑，含解锁验证闸门）。 */
    onToggleLock: (String, Boolean) -> Unit,
    /** 编辑备注 / 跳转 / 限额。 */
    onEdit: (LockRule) -> Unit,
    /** 编辑解锁条件（解锁验证弹层）。 */
    onEditUnlock: (LockRule) -> Unit,
    /** 删除（内部走 ruleDeleteRequest，含验证闸门）。 */
    onDeleteRequest: (LockRule) -> Unit,
    /** 配置该界面的自动锁闭（重锁延迟，界面级优先于全局）。 */
    onRelockConfig: (LockRule) -> Unit,
) {
    val memberRules = AppStore.rules.filter { it.packageName == pkg }
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
                            "已拦截 ${memberRules.count { it.locked }}/${memberRules.size} 个界面",
                            style = MaterialTheme.typography.labelSmall
                                .copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onClose) { Text("关闭") }
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    memberRules.forEach { rule ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            // 第一行：页面名称 + 开关
                            Row(
                                Modifier.fillMaxWidth(),
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
                                            if (rule.locked) " · 拦截中" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Switch(
                                    checked = rule.locked,
                                    onCheckedChange = { want -> onToggleLock(rule.activityName, want) }
                                )
                            }
                            // 第二行：四个操作按钮（居中均布）
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                IconButton(onClick = { onEditUnlock(rule) }) {
                                    Icon(
                                        Icons.Default.Key,
                                        contentDescription = "解锁条件",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                IconButton(onClick = { onRelockConfig(rule) }) {
                                    Icon(
                                        Icons.Default.Schedule,
                                        contentDescription = "自动锁闭",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                IconButton(onClick = { onEdit(rule) }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "编辑",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                IconButton(onClick = { onDeleteRequest(rule) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                }
            }
        }
    }
}
