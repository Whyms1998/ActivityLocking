package com.activitylock.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.BackupStore
import com.activitylock.app.core.VerifyGate
import com.activitylock.app.core.WebDavSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据同步：**WebDAV 云端** + **本地文件** 两种搬迁方式，共用同一份备份包格式。
 *
 * 两条通路的分工：
 *  - 云端：同一台手机定期备份、或两台手机都连同一个 WebDAV 目录；
 *  - 文件：一次性搬家，文件放自己手里（U 盘 / 电脑 / 网盘）。
 *
 * 所有读写都在 [BackupStore] / [WebDavSync] 里，本页只负责交互与验证闸门：
 * 上传/下载都是**能一次性换掉全部规则**的动作，等于批量解锁，必须先过验证。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val initial = remember { WebDavSync.load(ctx) }
    var base by remember { mutableStateOf(initial.base) }
    var user by remember { mutableStateOf(initial.user) }
    var pass by remember { mutableStateOf(initial.pass) }
    var path by remember { mutableStateOf(initial.path) }

    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf("处理中…") }
    var message by remember { mutableStateOf<String?>(null) }
    var lastExport by remember { mutableStateOf(BackupStore.lastExportAt(ctx)) }
    var lastSync by remember { mutableStateOf(WebDavSync.lastSyncAt(ctx)) }
    var hasSnapshot by remember { mutableStateOf(BackupStore.hasSnapshot(ctx)) }

    // 待确认的导入：本地文件与云端下载共用（先看清内容，再验证，再覆盖）
    var pendingRaw by remember { mutableStateOf<String?>(null) }
    var pendingFrom by remember { mutableStateOf("") }
    var pendingPreview by remember { mutableStateOf<BackupStore.Preview?>(null) }

    var gate by remember { mutableStateOf<(() -> Unit)?>(null) }
    var gateTitle by remember { mutableStateOf("解锁验证") }
    var gateHint by remember { mutableStateOf("") }

    fun cfgNow() = WebDavSync.Cfg(base, user, pass, path)

    fun needVerify(title: String, hint: String, action: () -> Unit) {
        val req = VerifyGate.bulkRequirement(ctx, AppStore.rules)
        if (req.isNone) action() else {
            gateTitle = title
            gateHint = hint
            gate = action
        }
    }

    /** 真正执行导入（本地文件 / 云端共用）。 */
    fun doImport(raw: String) {
        scope.launch {
            busyText = "正在写入配置…"
            busy = true
            val r = withContext(Dispatchers.IO) { runCatching { BackupStore.apply(ctx, raw) } }
            busy = false
            r.onSuccess {
                hasSnapshot = BackupStore.hasSnapshot(ctx)
                message = "已导入" + summaryOf(it)
            }.onFailure { message = it.message ?: "导入失败" }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyText = "正在生成备份…"
            busy = true
            val text = withContext(Dispatchers.IO) { runCatching { BackupStore.export(ctx) }.getOrNull() }
            if (text == null) {
                busy = false
                message = "导出失败：无法生成备份内容"
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } != null
                }.getOrDefault(false)
            }
            busy = false
            if (ok) {
                BackupStore.markExported(ctx)
                lastExport = BackupStore.lastExportAt(ctx)
                message = "已导出到所选位置"
            } else {
                message = "导出失败：文件无法写入（换个位置再试）"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busyText = "正在读取文件…"
            busy = true
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            busy = false
            if (raw == null) {
                message = "读取文件失败"
                return@launch
            }
            runCatching { BackupStore.parse(raw) }
                .onSuccess {
                    pendingRaw = raw
                    pendingFrom = "文件"
                    pendingPreview = it.preview()
                }
                .onFailure { message = it.message ?: "文件不是有效的备份" }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("数据同步", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "把配置搬到云端或新手机",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "同步内容：锁定清单、解锁验证、监督额度、定时计划、自动锁闭、特赦配额、锁定历史。" +
                            "不含使用时长和今天已用的次数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---------------- WebDAV 云端 ----------------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("WebDAV 云端", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "支持坚果云、Nextcloud、群晖等。坚果云要填「应用密码」，不是登录密码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = base,
                        onValueChange = { base = it.trim() },
                        label = { Text("服务器地址") },
                        placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = user,
                            onValueChange = { user = it.trim() },
                            label = { Text("账号") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = pass,
                            onValueChange = { pass = it },
                            label = { Text("密码") },
                            singleLine = true,
                            enabled = !busy,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it.trim() },
                        label = { Text("远端文件") },
                        placeholder = { Text(WebDavSync.DEFAULT_PATH) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (base.ifBlank { "" }.startsWith("http://")) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "http 会用明文传输账号密码，建议改用 https",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                message = null
                                val c = cfgNow()
                                WebDavSync.save(ctx, c)
                                scope.launch {
                                    busyText = "正在连接…"
                                    busy = true
                                    val r = withContext(Dispatchers.IO) { runCatching { WebDavSync.test(c) } }
                                    busy = false
                                    message = r.getOrElse { it.message ?: "连接失败" }
                                }
                            },
                            enabled = !busy
                        ) { Text("测试连接") }
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = {
                                message = null
                                val c = cfgNow()
                                needVerify("上传到云端", "云端备份包含解锁密码，上传前需要先通过验证") {
                                    scope.launch {
                                        busyText = "正在上传…"
                                        busy = true
                                        val r = withContext(Dispatchers.IO) {
                                            runCatching {
                                                WebDavSync.save(ctx, c)
                                                val text = BackupStore.export(ctx)
                                                WebDavSync.upload(ctx, c, text)
                                            }
                                        }
                                        busy = false
                                        r.onSuccess {
                                            lastSync = WebDavSync.lastSyncAt(ctx)
                                            message = "已上传到云端"
                                        }.onFailure { message = it.message ?: "上传失败" }
                                    }
                                }
                            },
                            enabled = !busy
                        ) { Text("上传") }
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = {
                                message = null
                                val c = cfgNow()
                                WebDavSync.save(ctx, c)
                                scope.launch {
                                    busyText = "正在读取云端…"
                                    busy = true
                                    val raw = withContext(Dispatchers.IO) {
                                        runCatching { WebDavSync.download(c) }
                                    }
                                    busy = false
                                    raw.onSuccess { body ->
                                        if (body == null) {
                                            message = "云端还没有备份，先点「上传」"
                                        } else {
                                            runCatching { BackupStore.parse(body) }
                                                .onSuccess {
                                                    pendingRaw = body
                                                    pendingFrom = "云端"
                                                    pendingPreview = it.preview()
                                                }
                                                .onFailure { message = it.message ?: "云端文件不是有效的备份" }
                                        }
                                    }.onFailure { message = it.message ?: "下载失败" }
                                }
                            },
                            enabled = !busy
                        ) { Text("恢复") }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (lastSync > 0) "上次云端同步：${fmtTime(lastSync)}" else "还没有上传过",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---------------- 本地文件 ----------------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("本地文件", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (lastExport > 0) "上次导出：${fmtTime(lastExport)}" else "还没有导出过",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                message = null
                                needVerify("导出备份", "导出会生成含解锁密码的文件，需要先通过验证") {
                                    exportLauncher.launch(BackupStore.suggestFileName())
                                }
                            },
                            enabled = !busy
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("导出到文件")
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = {
                                message = null
                                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                            },
                            enabled = !busy
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("从文件导入")
                        }
                    }
                }
            }

            if (hasSnapshot) {
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "撤销上一次导入",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                message = null
                                scope.launch {
                                    busyText = "正在还原…"
                                    busy = true
                                    val r = withContext(Dispatchers.IO) { runCatching { BackupStore.rollback(ctx) } }
                                    busy = false
                                    r.onSuccess {
                                        hasSnapshot = BackupStore.hasSnapshot(ctx)
                                        message = "已还原到导入前的配置" + summaryOf(it)
                                    }.onFailure { message = it.message ?: "还原失败" }
                                }
                            }, enabled = !busy) { Text("还原") }
                        }
                        Text(
                            "导入前自动存了一份当时的配置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            message?.let {
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "备份文件和云端备份里的解锁密码都是明文，仅用于还原，不要发给别人。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
        }

        if (busy) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("请稍候") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(busyText, style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = {}
            )
        }
    }

    // 导入确认（文件 / 云端共用）
    pendingPreview?.let { pv ->
        AlertDialog(
            onDismissRequest = { pendingRaw = null; pendingPreview = null },
            title = { Text("导入这份备份？") },
            text = {
                Column {
                    Text(
                        "来自${pendingFrom.ifBlank { "备份" }} · v${pv.versionName} · ${fmtTime(pv.exportedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (pv.counts.isEmpty()) "这份备份只有设置项"
                        else pv.counts.joinToString(" · ") { "${it.first} ${it.second}" },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("本机现有配置会被完全替换，包括解锁密码。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val raw = pendingRaw
                    pendingRaw = null
                    pendingPreview = null
                    if (raw != null) {
                        needVerify("导入备份", "导入会替换全部规则与解锁密码，需要先通过验证") {
                            doImport(raw)
                        }
                    }
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRaw = null; pendingPreview = null }) { Text("取消") }
            }
        )
    }

    gate?.let { action ->
        com.activitylock.app.ui.components.UnlockVerifyDialog(
            requirement = VerifyGate.bulkRequirement(ctx, AppStore.rules),
            title = gateTitle,
            hint = gateHint,
            enforceWindow = false,
            onConfirmed = {
                gate = null
                action()
            },
            onDismiss = { gate = null }
        )
    }
}

private fun summaryOf(p: BackupStore.Preview): String =
    if (p.counts.isEmpty()) "" else "：" + p.counts.joinToString(" · ") { "${it.first} ${it.second}" }

private fun fmtTime(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
