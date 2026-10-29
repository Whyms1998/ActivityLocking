package com.activitylock.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.FocusEngine
import com.activitylock.app.core.FocusState
import com.activitylock.app.core.FocusSession
import com.activitylock.app.core.VerifyGate
import com.activitylock.app.ui.components.DurationField
import com.activitylock.app.ui.components.DurationUnit
import com.activitylock.app.ui.components.UnlockVerifyDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 专注会话：开始后目标 App 整包临时拦截，到时自动恢复。
 * 两种模式：锁定选定 App / 白名单（锁全部第三方，仅放行勾选的 App）。
 * 严格模式下提前结束也需要通过验证（未配置验证时强制答题）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var session by remember { mutableStateOf(FocusState.load(ctx)) }
    val lastCfg = remember { FocusState.lastConfig(ctx) }
    var mode by remember { mutableStateOf(lastCfg?.mode ?: "selected") }
    // ★ v1.24.9：专注时长改用全项目统一的 [DurationField]。
    //   原来是 −/＋ 每次 ±5 分钟、上限 240 —— 想专注 3 小时得点 43 次，
    //   而且比引擎真正接受的 1–1440 分钟更窄（[FocusSession.start] 校验 1..1440）。
    //   现在秒存储、分钟/小时自由选，越界只在回显里提示并禁用开始按钮。
    var durationSec by remember { mutableStateOf((lastCfg?.durationMin ?: 25) * 60) }
    val durationMin = durationSec / 60
    val durationOk = durationMin in 1..1440
    var strict by remember { mutableStateOf(lastCfg?.strict ?: true) }
    var selected by remember { mutableStateOf(lastCfg?.members?.toSet() ?: setOf<String>()) }
    var codeDialog by remember { mutableStateOf<String?>(null) } // "gen" | "verify"
    var generatedCode by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var earlyStopVerify by remember { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // 运行中：每秒刷新剩余时间，到点自动收尾
    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        while (System.currentTimeMillis() < s.endAt) {
            remaining = ((s.endAt - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
            delay(1000)
        }
        FocusEngine.reconcile(ctx)
        session = FocusState.load(ctx)
    }

    fun start() {
        starting = true
        scope.launch {
            // ★ FocusEngine.start 已改为 suspend 并在内部切到 IO：
            //   whitelist 模式要枚举全部已安装应用，再对每个目标包跑一次
            //   Shizuku force-stop，原来直接在点击回调（主线程）里跑会 ANR。
            val err = FocusEngine.start(ctx, mode, selected, durationMin, strict)
            error = err
            starting = false
            if (err == null) session = FocusState.load(ctx)
        }
    }

    fun endEarly() {
        val s = session ?: return
        session = null
        FocusEngine.end(ctx, s, completed = false)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("专注会话", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        val s = session
        if (s != null) {
            // —— 会话进行中 ——
            // ★ 显示值必须读 remaining 这个 state（由上面的 LaunchedEffect 每秒写入），
            //   写入才会触发重组。原实现把时间差重算一遍、从不读 remaining，
            //   导致每秒的写入无人消费 → 倒计时与进度在界面上完全静止。
            //   remaining 尚未被初始化（首帧/刚切换会话）时回退为实时计算，避免闪 00:00。
            val remainingSec = remaining.takeIf { it > 0 }
                ?: ((s.endAt - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
            val remainingMin = remainingSec / 60
            val remainingSecPart = remainingSec % 60
            val progress = (remainingSec.toFloat() / (s.durationMin * 60)).coerceIn(0f, 1f)
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(24.dp))
                Text(
                    String.format("%02d:%02d", remainingMin, remainingSecPart),
                    style = MaterialTheme.typography.displayLarge
                        .copy(fontFeatureSettings = "tnum", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    // ★ 规范 §1：原来「白名单模式 · N 个 App 已拦截」是中点串两个维度
                    if (s.mode == "whitelist") "白名单模式，已拦截 ${s.targets.size} 个 App"
                    else "已拦截 ${s.targets.size} 个 App",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (s.strict) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "严格模式：提前结束需要通过验证",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = {
                        if (s.strict) earlyStopVerify = true else endEarly()
                    },
                    enabled = !starting
                ) { Text("提前结束") }
                Text(
                    "会话结束后自动恢复全部 App",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        } else {
            // —— 会话配置 ——
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp)
            ) {
                Text("模式", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Row {
                    FilterChip(
                        selected = mode == "selected",
                        onClick = { mode = "selected" },
                        label = { Text("锁定选定 App") }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = mode == "whitelist",
                        onClick = { mode = "whitelist" },
                        label = { Text("白名单（锁全部第三方）") }
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("时长", style = MaterialTheme.typography.titleSmall)
                DurationField(
                    seconds = durationSec,
                    onChange = { durationSec = it },
                    units = listOf(DurationUnit.MIN, DurationUnit.HOUR),
                    modifier = Modifier.padding(top = 4.dp),
                    equivalent = { sec ->
                        if (sec / 60 !in 1..1440) "时长需在 1 分钟 – 24 小时之间"
                        else "专注 " + relockDelayLabel(sec) + "，到时自动恢复"
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = strict, onCheckedChange = { want ->
                        when {
                            want && com.activitylock.app.core.SelfCode.hasCode(ctx) -> {
                                // 已有自律码：开启无需验证，但保持提示
                                strict = true
                            }
                            want -> {
                                // 首次开启：生成自律码，展示一次
                                generatedCode = (100000..999999).random().toString()
                                codeDialog = "gen"
                            }
                            com.activitylock.app.core.SelfCode.hasCode(ctx) -> {
                                // 关闭严格模式需要自律码
                                codeInput = ""
                                codeDialog = "verify"
                            }
                            else -> strict = false
                        }
                    })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("严格模式", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (com.activitylock.app.core.SelfCode.hasCode(ctx)) "已绑定自律码：关闭需输入"
                            else "开启将生成自律码，关闭需输入",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    if (mode == "selected") "选择要拦截的 App（已锁定规则的 App）"
                    else "勾选白名单（专注期间可正常使用的 App）",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("搜索") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
                val allPkgs = remember(AppStore.rules, mode) {
                    if (mode == "selected") {
                        AppStore.rules.map { it.packageName }.distinct()
                    } else {
                        AppStore.installedPackages().filter { it != FocusEngine.SELF_PKG }.sorted()
                    }
                }
                val visible = allPkgs.filter {
                    val label = AppStore.findApp(it)?.label ?: it
                    label.contains(filter.trim(), true) || it.contains(filter.trim(), true)
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(visible, key = { it }) { pkg ->
                        val checked = pkg in selected
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selected = if (checked) selected - pkg else selected + pkg
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = {
                                selected = if (checked) selected - pkg else selected + pkg
                            })
                            Text(
                                AppStore.findApp(pkg)?.label ?: pkg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                Button(
                    onClick = { start() },
                    // 时长超出引擎接受范围（1–1440 分钟）时禁用，回显里已说明原因
                    enabled = !starting && selected.isNotEmpty() && durationOk,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (starting) "启动中…"
                        else "开始专注 " + relockDelayLabel(durationSec),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (earlyStopVerify) {
        val s = session
        if (s != null) {
            var bulk = VerifyGate.bulkRequirement(ctx, AppStore.rules)
            val requirement = if (bulk.isNone) {
                com.activitylock.app.core.VerifyGate.Requirement(
                    VerifyGate.METHOD_QUIZ, 0, emptyList()
                )
            } else bulk
            UnlockVerifyDialog(
                requirement = requirement,
                title = "提前结束验证",
                hint = "严格模式下提前结束需要通过验证",
                onConfirmed = {
                    earlyStopVerify = false
                    endEarly()
                },
                onDismiss = { earlyStopVerify = false }
            )
        }
    }
}
