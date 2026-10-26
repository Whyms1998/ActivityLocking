package com.activitylock.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.activitylock.app.ui.components.DurationField
import com.activitylock.app.ui.components.DurationUnit
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
                                "打开触发 App 连续满 N 分钟，自动打开学习 App。\n" +
                                    "切去其他 App 在宽限时间内回来会接着算（回消息不受影响），超过才重新计时；" +
                                    "宽限时间在新增规则时自定义（精确到秒）。",
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
                                    // ★ v1.24.9：时长读数统一（180 分钟 → 「3 小时」）
                                    "打开「" + (AppStore.findApp(c.pkg)?.label ?: c.pkg.substringAfterLast('.')) +
                                        "」连续 " + relockDelayLabel(c.limitMin * 60) + " → 当日锁定（次日恢复）" + graceSuffix(c.graceSec)
                                } else {
                                    "打开「" + (AppStore.findApp(c.pkg)?.label ?: c.pkg.substringAfterLast('.')) +
                                        "」" + relockDelayLabel(c.limitMin * 60) + "后自动打开「" + sessionTargetLabel(c.target) + "」" +
                                        // ★ 规范 §1：原来拼的是 "· 当日 N 次后锁定"（中点另起一条规则）→ 用逗号承接
                                        (if (c.maxJumps > 0) "，当日 " + c.maxJumps + " 次后锁定" else "") +
                                        graceSuffix(c.graceSec)
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
                                        // ★ 规范 §1：原来「今日已用 X/Y 分钟 · 已超额」——超额是结论不是并列项
                                        if (used >= min) "今日已用 $used/$min 分钟（已超额）"
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
            onConfirm = { trigger, target, min, maxJ, mode, grace ->
                AppStore.upsertSession(
                    SessionCfg(
                        pkg = trigger, target = target, limitMin = min,
                        maxJumps = maxJ, mode = mode, graceSec = grace
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
    // ★ v1.24.9：分钟数改为统一的 [DurationField]（分钟 / 小时，无「秒」档 ——
    //   限额按分钟统计，放开秒会让用户填出一个存不进去的值）。
    //   单位只留这两档，所以秒数永远是 60 的整数倍，落盘不做任何取整。
    var limitSec by remember { mutableStateOf(20 * 60) }
    val limitMin = limitSec / 60
    val overDay = limitMin > 1440
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加限额") },
        text = {
            // ★ 分钟数输入框会唤起键盘 → 让 Dialog window 随键盘收缩
            com.activitylock.app.ui.components.DialogImeResize()
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
                DurationField(
                    seconds = limitSec,
                    onChange = { limitSec = it },
                    units = listOf(DurationUnit.MIN, DurationUnit.HOUR),
                    equivalent = { sec ->
                        if (sec <= 0) "请填写额度" else "每天可用 " + relockDelayLabel(sec)
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // ★ 「每日」限额的天然上限就是一天 —— 这是定义边界而不是随手限幅，
                    //   因此保留但**明说**，不再静默钳制。
                    if (overDay) "超出上限：每天最多 24 小时（1440 分钟）"
                    else "超额后当天拦截该 App，次日 0 点重置",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overDay) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotBlank() && limitMin in 1..1440,
                onClick = { onConfirm(selected, limitMin) }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun graceSuffix(sec: Int): String =
    // ★ 规范 §1：原来返回 "· 宽限 N 秒"（中点另起一条规则）→ 逗号承接更顺
    // ★ v1.24.9：可自由填时长后统一走 relockDelayLabel（3600 读作「1 小时」而非「3600 秒」）
    if (sec == 120) "" else "，宽限 " + relockDelayLabel(sec)

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
    onConfirm: (String, String, Int, Int, String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query1 by remember { mutableStateOf("") }
    var query2 by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<String?>(null) }
    // ★ v1.24.9：两个「时长」都改秒存储 + 统一输入组件（分钟/小时、秒/分钟/小时）
    var limitSec by remember { mutableStateOf(60) }     // 连续使用：默认 1 分钟
    var graceSec by remember { mutableStateOf(120) }     // 离开宽限期：默认 120 秒
    var maxJumps by remember { mutableStateOf(3) }
    var customJumps by remember { mutableStateOf(false) }
    var showCustomJumpsDialog by remember { mutableStateOf(false) }
    var customJumpsText by remember { mutableStateOf("") }
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
            // ★ 目标包名等输入框会唤起键盘 → 让 Dialog window 随键盘收缩
            com.activitylock.app.ui.components.DialogImeResize()
            // 内容整体可滚动（PickerList 内部自带滚动，超出屏幕的部分靠这里拖动）
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                // ★ v1.24.9：原来「连续使用（分钟）/ 离开宽限期（秒）」两个写死单位的
                //   输入框（还各自带一套范围文案）统一为 [DurationField]，
                //   秒 / 分钟 / 小时自由选，不再有范围限制。
                Text("连续使用多久算刷满", style = MaterialTheme.typography.labelMedium)
                DurationField(
                    seconds = limitSec,
                    onChange = { limitSec = it },
                    units = listOf(DurationUnit.MIN, DurationUnit.HOUR),
                    equivalent = { sec ->
                        if (sec <= 0) "请填写时长" else "连续使用满 " + relockDelayLabel(sec)
                    },
                )
                Spacer(Modifier.height(10.dp))
                Text("离开宽限期", style = MaterialTheme.typography.labelMedium)
                DurationField(
                    seconds = graceSec,
                    onChange = { graceSec = it },
                    equivalent = { sec ->
                        if (sec <= 0) "切出即重新计时"
                        else "切出 " + relockDelayLabel(sec) + " 内回来接着算"
                    },
                )
                if (mode == "jump") {
                    Spacer(Modifier.height(8.dp))
                    Text("当日第 N 次跳转后锁定该 App（次日恢复）", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // ★ v1.24.8（用户 2026-10-13）：预设里的「3 次」改成「自定义次数」。
                        //   原来 listOf(0,1,3) 里硬塞了一个 3，覆盖不了用户真正想要的数
                        //   （比如 5 次、10 次）。现在预设只留两个语义明确的档
                        //   （不锁定 / 1 次），其余全部走「自定义」。
                        listOf(0 to "不锁定", 1 to "1 次").forEach { (n, label) ->
                            FilterChip(
                                selected = !customJumps && maxJumps == n,
                                onClick = {
                                    maxJumps = n
                                    customJumps = false
                                },
                                label = { Text(label, maxLines = 1) }
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        FilterChip(
                            selected = customJumps,
                            onClick = {
                                customJumpsText = if (customJumps) maxJumps.toString() else ""
                                showCustomJumpsDialog = true
                            },
                            label = {
                                Text(if (customJumps) "自定义 " + maxJumps + " 次" else "自定义")
                            }
                        )
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
                // ★ v1.24.9：连续使用至少 1 分钟（分钟粒度），宽限期任意（0 = 切出即清零）
                enabled = trigger != null && (mode == "lock" || target != null) &&
                    limitSec >= 60 && maxJumps in 0..99 && graceSec >= 0,
                onClick = {
                    onConfirm(
                        trigger!!, target ?: "", limitSec / 60,
                        maxJumps, mode, graceSec
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
    if (showCustomJumpsDialog) {
        AlertDialog(
            onDismissRequest = { showCustomJumpsDialog = false },
            title = { Text("自定义锁定次数") },
            text = {
                // ★ 次数输入框会唤起键盘 → 让 Dialog window 随键盘收缩
                com.activitylock.app.ui.components.DialogImeResize()
                Column {
                    Text(
                        "当日第 N 次跳转后锁定该 App",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customJumpsText,
                    onValueChange = { customJumpsText = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("次数") },
                    singleLine = true,
                    // ★ v1.24.7：与上面「每日限额」同一套口径 —— 按钮禁用要说理由。
                    // ★ v1.29.1：删掉常驻的「范围 1 – 99 次」（输入被 take(2) 限死，本来也越不了界）。
                    isError = (customJumpsText.toIntOrNull() ?: 0) !in 1..99,
                    modifier = Modifier.fillMaxWidth()
                )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = (customJumpsText.toIntOrNull() ?: 0) in 1..99,
                    onClick = {
                        maxJumps = customJumpsText.toInt()
                        customJumps = true
                        showCustomJumpsDialog = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCustomJumpsDialog = false
                    if (!customJumps) maxJumps = 3
                }) { Text("取消") }
            }
        )
    }
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
