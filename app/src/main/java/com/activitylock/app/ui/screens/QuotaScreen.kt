package com.activitylock.app.ui.screens

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.activitylock.app.core.AppEntry
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.SuperviseEngine
import com.activitylock.app.core.SuperviseRules
import com.activitylock.app.core.SuperviseTarget
import com.activitylock.app.core.SuperviseTask
import com.activitylock.app.core.TargetUsage
import com.activitylock.app.core.UsageTracker
import com.activitylock.app.ui.components.AppIcon
import com.activitylock.app.ui.components.DialogImePan
import com.activitylock.app.ui.components.DialogImeResize
import com.activitylock.app.ui.components.DurationField
import com.activitylock.app.ui.components.DurationUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * APP 监督（★ v1.32.0 重写，替代原「每日限额」页）。
 *
 * 模型：任务 = 一组监督对象（整包 / 单界面）+ 一套规则 + 生效时段。
 * 与「锁定」解耦——只监督不需要把 App 加进收藏夹，平时打开零摩擦。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotaScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val tasks = AppStore.tasks
    val hasPerm = UsageTracker.hasPermission(ctx)
    var editing by remember { mutableStateOf<SuperviseTask?>(null) }
    var pendingDelete by remember { mutableStateOf<SuperviseTask?>(null) }

    // 读数每 5 秒刷一次（系统事件是权威源，读实时算，不依赖任何缓存）
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            tick++
        }
    }
    val stats by produceState<Map<String, TargetUsage>>(emptyMap(), tasks, tick, hasPerm) {
        value = if (!hasPerm || tasks.isEmpty()) emptyMap() else withContext(Dispatchers.IO) {
            val targets = tasks.flatMap { it.targets }
            runCatching {
                UsageTracker.usageForAll(
                    ctx,
                    SuperviseEngine.dayStartMs(System.currentTimeMillis()),
                    targets.filter { it.isWholeApp }.map { it.pkg }.toSet(),
                    targets.filterNot { it.isWholeApp }.map { it.key }.toSet(),
                )
            }.getOrDefault(emptyMap())
        }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("APP 监督", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editing = SuperviseTask(name = "监督任务 ${tasks.size + 1}")
                    }) { Icon(Icons.Default.Add, contentDescription = "新建监督任务") }
                }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (!hasPerm) {
                item { PermissionCard() }
            }
            if (tasks.isEmpty()) {
                item { EmptyState() }
            }
            items(tasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    stats = stats,
                    onEdit = { editing = task },
                    onToggle = { AppStore.setTaskEnabled(task.id, it) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    editing?.let { t ->
        TaskEditSheet(
            initial = t,
            isNew = tasks.none { it.id == t.id },
            onDismiss = { editing = null },
            onSave = {
                AppStore.upsertTask(it)
                editing = null
            },
            onDelete = {
                // 删除 = 解除监督，属削弱性动作：该任务下有对象正在被拦时必须先过验证
                val blocked = SuperviseEngine.blocks(ctx).any { b ->
                    t.targets.any { x -> x.key == b.key }
                }
                if (blocked) pendingDelete = t
                else {
                    AppStore.removeTask(t.id)
                    editing = null
                }
            },
        )
    }

    pendingDelete?.let { t ->
        com.activitylock.app.ui.components.UnlockVerifyDialog(
            requirement = com.activitylock.app.core.VerifyGate.bulkRequirement(ctx, AppStore.rules),
            title = "删除监督任务验证",
            hint = "该任务下有 App 正在被拦截，删除等于解除监督",
            onConfirmed = {
                AppStore.removeTask(t.id)
                pendingDelete = null
                editing = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun PermissionCard() {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("需要「使用情况访问权限」", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "统计各 App / 界面的使用时长必须授予此权限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { UsageTracker.openPermissionSettings(ctx) }) { Text("去授权") }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("还没有监督任务", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "点右上角「＋」新建：选一组 App 或界面，设定每天可用多久、打开几次",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskCard(
    task: SuperviseTask,
    stats: Map<String, TargetUsage>,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // 单对象任务直接用对象名（如「微信 › 朋友圈」），比用户随手起的任务名更可读
                    Text(
                        if (task.targets.size == 1) task.targets[0].shortName() else task.name,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(2.dp))
                    // 单对象任务：标题已经是对象名，这里再列一遍就重复了
                    if (task.targets.size != 1) {
                        Text(
                            targetSummary(task),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Switch(checked = task.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                ruleSummary(task),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // 今日读数：最多两条，超额标红
            val lines = readout(task, stats)
            if (lines.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                for (l in lines) {
                    Text(
                        l.first,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (l.second) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun targetSummary(task: SuperviseTask): String {
    if (task.targets.isEmpty()) return "未添加监督对象"
    val names = task.targets.take(3).joinToString("、") { it.shortName() }
    return if (task.targets.size > 3) "$names 等 ${task.targets.size} 项" else names
}

private fun ruleSummary(task: SuperviseTask): String {
    val r = task.rules
    val parts = ArrayList<String>()
    if (r.forbid) parts.add("禁止使用")
    if (r.windowLimitSec != null) parts.add("时段内 ${minLabel(r.windowLimitSec)}")
    if (r.windowLimitCnt != null) parts.add("时段内 ${r.windowLimitCnt} 次")
    if (r.dayLimitSec != null) parts.add("每天 ${minLabel(r.dayLimitSec)}")
    if (r.dayLimitCnt != null) parts.add("每天 ${r.dayLimitCnt} 次")
    if (parts.isEmpty()) return "未设置规则"
    return parts.joinToString(" · ") + " · " + task.windowLabel()
}

private fun minLabel(sec: Int): String =
    if (sec % 3600 == 0) "${sec / 3600} 小时" else "${sec / 60} 分钟"

/** 今日读数：优先显示已超额的对象，最多两条。 */
private fun readout(task: SuperviseTask, stats: Map<String, TargetUsage>): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    for (t in task.targets) {
        val st = stats[t.key] ?: TargetUsage()
        val r = task.rules
        val overTime = (r.dayLimitSec != null && st.sec >= r.dayLimitSec) ||
            (r.windowLimitSec != null && st.sec >= r.windowLimitSec)
        val overCnt = (r.dayLimitCnt != null && st.opens >= r.dayLimitCnt) ||
            (r.windowLimitCnt != null && st.opens >= r.windowLimitCnt)
        val over = overTime || overCnt
        if (!over && out.size >= 2) continue
        val bits = ArrayList<String>()
        if (r.dayLimitSec != null || r.windowLimitSec != null) {
            val limitSec = (r.dayLimitSec ?: r.windowLimitSec)!!.toLong()
            bits.add("${st.sec / 60}/${limitSec / 60} 分钟")
        }
        if (r.dayLimitCnt != null || r.windowLimitCnt != null) {
            val limitCnt = r.dayLimitCnt ?: r.windowLimitCnt!!
            bits.add("${st.opens}/$limitCnt 次")
        }
        val prefix = if (task.targets.size == 1) "" else t.shortName() + " "
        out.add((prefix + bits.joinToString(" · ") + if (over) " 已用完" else "") to over)
        if (out.size >= 3) break
    }
    return out
}

// ---------------- 编辑面板 ----------------

@Composable
private fun TaskEditSheet(
    initial: SuperviseTask,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (SuperviseTask) -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial.name) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var startMin by remember { mutableStateOf(initial.windowStart) }
    var endMin by remember { mutableStateOf(initial.windowEnd) }
    var rules by remember { mutableStateOf(initial.rules) }
    var targets by remember { mutableStateOf(initial.targets) }
    var adding by remember { mutableStateOf(false) }

    fun fmt(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    val canSave = name.isNotBlank() && targets.isNotEmpty() && rules.active

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            Modifier.fillMaxWidth().fillMaxHeight(0.94f),
            color = MaterialTheme.colorScheme.surface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            DialogImeResize()
            Column(Modifier.fillMaxSize()) {
                // 顶栏
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Text(
                        if (isNew) "新建监督任务" else "编辑监督任务",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    TextButton(enabled = canSave, onClick = {
                        onSave(
                            initial.copy(
                                name = name.trim(),
                                enabled = enabled,
                                windowStart = startMin,
                                windowEnd = endMin,
                                rules = rules,
                                targets = targets,
                            )
                        )
                    }) { Text("保存") }
                }
                HorizontalDivider()
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("任务名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("任务开关", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    // 生效时段
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("监督时间范围", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (startMin == 0 && endMin == 1439) "全天" else "只在此时段内监督",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            TimePickerDialog(ctx, { _, h, m -> startMin = h * 60 + m }, startMin / 60, startMin % 60, true).show()
                        }) { Text(fmt(startMin)) }
                        Text("–", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            TimePickerDialog(ctx, { _, h, m -> endMin = h * 60 + m }, endMin / 60, endMin % 60, true).show()
                        }) { Text(fmt(endMin)) }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    // 监督对象
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "监督对象（${targets.size}）",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { adding = true }) { Text("＋ 添加") }
                    }
                    if (targets.isEmpty()) {
                        Text(
                            "选 App 可整个应用监督，也可以只监督某个界面",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    for (t in targets) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (t.isWholeApp) AppIcon(t.pkg, 24.dp) else Spacer(Modifier.width(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                t.shortName(),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { targets = targets.filterNot { x -> x.key == t.key } }) {
                                Icon(Icons.Default.Close, contentDescription = "移除", modifier = Modifier.width(18.dp))
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    // 监督规则
                    Text("监督规则", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rules.forbid, onCheckedChange = {
                            rules = rules.copy(forbid = it)
                        })
                        Text("禁止使用", style = MaterialTheme.typography.bodyLarge)
                    }
                    DurationRuleRow(
                        title = "当前时间段时长",
                        sec = rules.windowLimitSec,
                        onChange = { rules = rules.copy(windowLimitSec = it) },
                    )
                    CountRuleRow(
                        title = "当前时间段次数",
                        value = rules.windowLimitCnt,
                        onChange = { rules = rules.copy(windowLimitCnt = it) },
                    )
                    DurationRuleRow(
                        title = "当天时长",
                        sec = rules.dayLimitSec,
                        onChange = { rules = rules.copy(dayLimitSec = it) },
                    )
                    CountRuleRow(
                        title = "当天次数",
                        value = rules.dayLimitCnt,
                        onChange = { rules = rules.copy(dayLimitCnt = it) },
                    )
                    if (!rules.active) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "至少设置一条规则才能保存",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    if (!isNew) {
                        HorizontalDivider()
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("删除任务", color = MaterialTheme.colorScheme.error) }
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }

    if (adding) {
        AddTargetDialog(
            existing = targets.map { it.key }.toSet(),
            onPick = {
                if (targets.none { x -> x.key == it.key }) targets = targets + it
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

/** 时长型规则行：勾选启用，值默认 1 小时。 */
@Composable
private fun DurationRuleRow(title: String, sec: Int?, onChange: (Int?) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = sec != null, onCheckedChange = {
                onChange(if (it) 3600 else null)
            })
            Text(title, style = MaterialTheme.typography.bodyLarge)
        }
        if (sec != null) {
            DurationField(
                seconds = sec,
                onChange = { onChange(it.coerceAtLeast(1)) },
                units = listOf(DurationUnit.MIN, DurationUnit.HOUR),
                showEquivalent = false,
                modifier = Modifier.padding(start = 44.dp, bottom = 8.dp),
            )
        }
    }
}

/** 次数型规则行：勾选启用，默认 60 次。 */
@Composable
private fun CountRuleRow(title: String, value: Int?, onChange: (Int?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = value != null, onCheckedChange = {
            if (it) {
                text = (value ?: 60).toString()
                onChange(value ?: 60)
            } else {
                text = ""
                onChange(null)
            }
        })
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) {
            OutlinedTextField(
                value = text,
                onValueChange = { s ->
                    text = s.filter { c -> c.isDigit() }.take(3)
                    text.toIntOrNull()?.let { onChange(it) }
                },
                singleLine = true,
                suffix = { Text("次") },
                isError = text.isNotBlank() && (text.toIntOrNull() ?: 0) !in 1..999,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.width(112.dp)
            )
        }
    }
}

// ---------------- 添加监督对象 ----------------

@Composable
private fun AddTargetDialog(
    existing: Set<String>,
    onPick: (SuperviseTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var app by remember { mutableStateOf<AppEntry?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            DialogImePan()
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (app != null) {
                        TextButton(onClick = { app = null }) { Text("‹ 返回") }
                    }
                    Text(
                        app?.label ?: "选择应用",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                if (app == null) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("搜索应用或包名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    // ★ 不能 take(N)：按 label 排序后中文 App 全在末尾，截断会让它们永远选不到
                    //   （真机复现：滚动到底也找不到「酷安」）。LazyColumn 本来就按需渲染。
                    val list = AppStore.apps.asSequence()
                        .filter {
                            query.isBlank() ||
                                it.label.contains(query, true) ||
                                it.packageName.contains(query, true)
                        }
                        .sortedBy { it.label }
                        .toList()
                    if (list.isEmpty()) {
                        Text(
                            AppStore.appsLoading.let { if (it) "正在读取应用列表…" else "没有匹配的应用" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(list, key = { it.packageName }) { a ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { app = a }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(a.packageName, 30.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    a.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (existing.contains(a.packageName)) {
                                    Text(
                                        "整包已加",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    val a = app!!
                    Text(
                        "只监督某个界面时，其他界面（如聊天）不受影响",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        item {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        onPick(SuperviseTarget(a.packageName))
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(a.packageName, 30.dp)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text("整个应用", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "该 App 的所有界面一起计入额度",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                        items(a.activities, key = { it.cls }) { act ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        onPick(SuperviseTarget(a.packageName, act.cls))
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        act.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        if (act.isLauncher) "入口界面 · 选它等于整包监督"
                                        else act.cls.substringAfterLast('.'),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
