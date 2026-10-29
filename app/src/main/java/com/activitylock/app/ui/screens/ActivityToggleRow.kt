package com.activitylock.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.LockEngine
import com.activitylock.app.core.SchemePresets
import com.activitylock.app.core.VerifyGate
import com.activitylock.app.ui.components.AppIcon
import com.activitylock.app.ui.components.UnlockConditionEditor
import kotlinx.coroutines.launch

/** 锁定确认：跳转目标 + 解锁条件一起设置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityToggleRow(
    packageName: String,
    cls: String,
    title: String,
    subtitle: String,
    isLauncher: Boolean = false,
    showAppIcon: Boolean = false,
) {
    val key = "$packageName/$cls"
    val rule = AppStore.rules.firstOrNull { it.packageName == packageName && it.activityName == cls }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember(key) { mutableStateOf(false) }
    // ★ 初始锁定态必须按「规则口径」判定，不能只看组件启用状态：
    //   block 模式（系统级拦截）不会去禁用组件，AppStore.isDisabled() 恒为 false，
    //   结果已拦截的界面在这个列表里被显示成「正常」、开关也是关的，
    //   与收藏夹里的实际状态自相矛盾。口径对齐 FavoritesScreen：
    //   block → 看 rule.locked；组件禁用模式 → 看组件状态。
    var locked by remember(key) {
        mutableStateOf(
            rule?.let { if (it.block) it.locked else AppStore.isDisabled(packageName, cls) } ?: false
        )
    }
    var mode by remember(key) { mutableStateOf(if (rule?.block == true) "block" else "pm") }
    var redirect by remember(key) {
        mutableStateOf(rule?.redirectUri)
    }
    var confirmLock by remember { mutableStateOf(false) }
    var vMode by remember(confirmLock) { mutableStateOf(rule?.verifyMode) }
    // ★ v1.24.9：等待秒数改 Int（输入框统一为 [DurationField]，草稿由组件自管）
    var vDelaySec by remember(confirmLock) {
        mutableStateOf(rule?.verifyDelaySec ?: VerifyGate.delaySeconds(context))
    }
    var vPwd by remember(confirmLock) { mutableStateOf(rule?.verifyPassword ?: "") }
    var vQuizCount by remember(confirmLock) {
        mutableStateOf((rule?.quizCount ?: VerifyGate.quizCount(context)).toString())
    }
    val globalReq = VerifyGate.globalRequirement(context)
    val globalDesc = when (globalReq.mode) {
        VerifyGate.MODE_NONE -> "已关闭"
        // ★ v1.24.9：等待可填秒/分/小时，读数统一走 relockDelayLabel
        VerifyGate.METHOD_DELAY -> "等待" + relockDelayLabel(globalReq.delaySec)
        VerifyGate.METHOD_QUIZ -> "答题"
        else -> "密码"
    }

    fun doApply(wantLock: Boolean, redirectUri: String?, vMode: String?, vDelay: Int?, vPwd: String?, vQuizCount: Int?, lockUntil: Long = 0L) {
        busy = true
        scope.launch {
            val o = LockEngine.apply(packageName, cls, wantLock, redirectUri)
            if (o.locked) {
                locked = true
                mode = o.mode
                // 重锁已带跳转的规则时无需传参，从规则读实际生效的跳转目标
                redirect = AppStore.rules
                    .firstOrNull { it.packageName == packageName && it.activityName == cls }
                    ?.redirectUri
                AppStore.toast(
                    when {
                        o.mode == "block" && redirect != null -> "已锁定：点桌面图标将直达搜索页"
                        o.mode == "block" -> "已改用拦截模式：该界面打开时自动退出"
                        else -> "锁定完成（组件已禁用）"
                    }
                )
            } else {
                locked = AppStore.isDisabled(packageName, cls)
                if (!locked) mode = "pm"
                AppStore.toast("操作失败：${o.detail}")
            }
            // 解锁条件随锁定一并保存（解锁/解锁失败时也保持一致）；解锁时清除到期时间
            AppStore.updateVerify(packageName, cls, vMode, vDelay, vPwd, vQuizCount)
            AppStore.updateLockUntil(packageName, cls, if (wantLock) lockUntil else 0L)
            busy = false
        }
    }

    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showAppIcon) {
                AppIcon(packageName, 40.dp)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isLauncher) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "桌面入口",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    when {
                        locked && mode == "block" && redirect != null -> "🚫 拦截模式（点图标直达搜索页）"
                        locked && mode == "block" -> "🚫 拦截模式（启动即拦截）"
                        locked -> "🔒 已禁用"
                        else -> "正常"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (locked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = locked,
                enabled = !busy,
                onCheckedChange = { wantLock ->
                    if (wantLock && !locked) {
                        confirmLock = true
                    } else {
                        doApply(wantLock, null, null, null, null, null, 0L)
                    }
                }
            )
        }
    }

    if (confirmLock) {
        LockLauncherDialog(
            packageName = packageName,
            isLauncher = isLauncher,
            vModeInit = vMode,
            vDelayInit = vDelaySec,
            vPwdInit = vPwd,
            vQuizCountInit = vQuizCount.trim().toIntOrNull(),
            onConfirm = { redirectUri, vm, vd, vp, vqc, rd, lu ->
                confirmLock = false
                doApply(true, redirectUri, vm, vd, vp, vqc, lu)
                if (vm == VerifyGate.METHOD_RANDOM && rd > 0) {
                    VerifyGate.setRandomDigits(context, rd)
                }
            },
            onTest = { uri ->
                runCatching {
                    val bar = uri.indexOf('|')
                    val intent = if (bar > 0) {
                        // ★ 「组件|uri」：显式组件 + data。
                        //   用于同一 scheme 注册在多个组件上的 App（酷安），
                        //   隐式写法会被系统判成二义并弹选择器。
                        Intent(Intent.ACTION_VIEW).apply {
                            component = android.content.ComponentName
                                .unflattenFromString(uri.substring(0, bar).trim())
                                ?: error("组件名格式无效")
                            data = Uri.parse(uri.substring(bar + 1).trim())
                        }
                    } else if (uri.contains("://")) {
                        Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    } else if (uri.contains('/')) {
                        Intent().setComponent(
                            android.content.ComponentName.unflattenFromString(uri)
                                ?: error("组件名格式无效")
                        )
                    } else {
                        Intent().setComponent(android.content.ComponentName(packageName, uri))
                    }
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { AppStore.toast("无法打开：${it.message}") }
            },
            onDismiss = { confirmLock = false }
        )
    }
}

/**
 * 校验跳转目标可否被拦截器（shell 身份）拉起：
 * 深链需本机有响应应用；类名/组件名要求 exported，未导出的界面 shell 无法启动。
 * 返回警告文案，null 表示可用。
 */
private fun validateRedirectTarget(context: android.content.Context, pkg: String, target: String): String? {
    if (target.isBlank()) return null
    val pm = context.packageManager
    val bar = target.indexOf('|')
    if (bar > 0) {
        // ★ 「组件|uri」：显式组件 + data。组件必须存在且 exported（watcher 以 shell 身份启动）；
        //   data 部分无法静态校验（显式 Intent 不走 filter 匹配），只要求是完整深链。
        val compPart = target.substring(0, bar).trim()
        val uriPart = target.substring(bar + 1).trim()
        if (!uriPart.contains("://")) return "「|」后面要写完整深链，例如 coolmarket://…"
        val cn2 = if (compPart.contains('/')) {
            android.content.ComponentName.unflattenFromString(compPart)
                ?: return "组件名格式无效，应为 包名/完整类名"
        } else {
            android.content.ComponentName(pkg, compPart)
        }
        val info2 = runCatching { pm.getActivityInfo(cn2, 0) }.getOrNull()
            ?: return "找不到该界面，请确认类名是否正确"
        return if (info2.exported) null
        else "该界面未导出（exported=false），拦截器无法拉起；请改用纯深链写法"
    }
    if (target.contains("://")) {
        val handled = runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)), 0) != null
        }.getOrDefault(false)
        return if (handled) null else "本机没有应用响应此深链，请先用「测试跳转」确认"
    }
    val cn = if (target.contains('/')) {
        android.content.ComponentName.unflattenFromString(target)
            ?: return "组件名格式无效，应为 包名/完整类名"
    } else {
        android.content.ComponentName(pkg, target)
    }
    val info = runCatching { pm.getActivityInfo(cn, 0) }.getOrNull()
        ?: return "找不到该界面，请确认类名是否正确"
    return if (info.exported) null
    else "该界面未导出（exported=false），拦截器无法拉起；请改用搜索页深链（推荐）或已导出的界面"
}

/** 锁定确认：跳转目标 + 解锁条件一起设置。任意界面锁定时均弹出。 */
@Composable
private fun LockLauncherDialog(
    packageName: String,
    isLauncher: Boolean,
    vModeInit: String?,
    vDelayInit: Int?,
    vPwdInit: String?,
    vQuizCountInit: Int?,
    onConfirm: (String?, String?, Int?, String?, Int?, Int, Long) -> Unit,
    onTest: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val suggestions = remember(packageName) { SchemePresets.suggestFor(packageName) }
    // 桌面入口默认推荐跳转；普通界面有预设建议时也默认跳转，否则默认仅拦截
    var redirectMode by remember { mutableStateOf(isLauncher || suggestions.isNotEmpty()) }
    var uri by remember { mutableStateOf(if (redirectMode) suggestions.firstOrNull()?.uri ?: "" else "") }
    val redirectUri = if (redirectMode) uri.trim().takeIf { it.isNotBlank() } else null
    val context = LocalContext.current
    // 校验跳转目标：未导出的界面 shell 拉不起来，必须用深链
    val warning = remember(uri, packageName) { validateRedirectTarget(context, packageName, uri.trim()) }
    // 历史数据里的「无需验证」按跟随全局处理（该选项已移除）
    var vMode by remember { mutableStateOf(vModeInit?.takeIf { it != VerifyGate.MODE_NONE }) }
    var vDelaySec by remember { mutableStateOf(vDelayInit ?: VerifyGate.delaySeconds(context)) }
    var vPwd by remember { mutableStateOf(vPwdInit ?: "") }
    var vQuizCount by remember { mutableStateOf((vQuizCountInit ?: VerifyGate.quizCount(context)).toString()) }
    var vRandomDigits by remember { mutableStateOf(VerifyGate.randomDigits(context).toString()) }
    // 到期自动解锁：0=不自动；-1=明天 8 点；其余=当前时刻+时长
    val untilOptions = remember {
        listOf(0L to "不自动", 3600_000L to "1 小时", 6L * 3600_000L to "6 小时",
            3L * 86_400_000L to "3 天", 7L * 86_400_000L to "7 天", -1L to "明天8点")
    }
    var untilChoice by remember { mutableStateOf(0L) }
    val untilMs: Long = when {
        untilChoice == -1L -> {
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 8)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
        untilChoice <= 0L -> 0L
        else -> System.currentTimeMillis() + untilChoice
    }
    val untilDesc: String? = if (untilMs > 0)
        "锁定至 " + java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(untilMs)) + "，到期自动解除"
    else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isLauncher) "锁定桌面入口" else "锁定该界面") },
        text = {
            // ★ 跳转目标输入框会唤起键盘 → 让 Dialog window 随键盘收缩，底部按钮不被遮挡
            // ★ v1.31.1：居中弹窗改 ADJUST_PAN（resize 随键盘收缩会反复重新居中 → 跳动）
            com.activitylock.app.ui.components.DialogImePan()
            Column(
                Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    if (isLauncher) "锁定后从桌面点击该 App：" else "锁定后该界面被打开时：",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = redirectMode, onClick = { redirectMode = true })
                    Text("跳转到指定页面", style = MaterialTheme.typography.bodyMedium)
                }
                if (redirectMode) {
                    OutlinedTextField(
                        value = uri,
                        onValueChange = { uri = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        label = { Text("跳转目标") },
                        placeholder = { Text("深链、界面类名，或 界面类名|深链") },
                        singleLine = true
                    )
                    warning?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (suggestions.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            suggestions.forEach { p ->
                                TextButton(onClick = { uri = p.uri }) { Text(p.name) }
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                    }
                    Row {
                        TextButton(
                            onClick = { if (uri.isNotBlank()) onTest(uri.trim()) },
                            enabled = uri.isNotBlank()
                        ) { Text("测试跳转") }
                        Text(
                            "组件保持启用，由系统级拦截生效",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .align(Alignment.CenterVertically)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = !redirectMode, onClick = { redirectMode = false })
                    Text("仅拦截（打开无反应）", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                UnlockConditionEditor(
                    mode = vMode,
                    delaySec = vDelaySec,
                    password = vPwd,
                    quizCount = vQuizCount.trim().toIntOrNull() ?: VerifyGate.quizCount(context),
                    randomDigits = vRandomDigits.trim().toIntOrNull() ?: VerifyGate.randomDigits(context),
                    onModeChange = { vMode = it },
                    onDelayChange = { vDelaySec = it },
                    onPasswordChange = { vPwd = it },
                    onQuizCountChange = { vQuizCount = it.toString() },
                    onRandomDigitsChange = { vRandomDigits = it.toString() },
                )
                Spacer(Modifier.height(12.dp))
                Text("到期自动解锁", style = MaterialTheme.typography.titleSmall)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    untilOptions.forEach { (optMs, optLabel) ->
                        FilterChip(
                            selected = untilChoice == optMs,
                            onClick = { untilChoice = optMs },
                            label = { Text(optLabel) }
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                }
                untilDesc?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    redirectUri,
                    vMode,
                    vDelaySec,
                    vPwd,
                    vQuizCount.trim().toIntOrNull(),
                    vRandomDigits.trim().toIntOrNull() ?: 6,
                    untilMs
                )
            }) { Text("锁定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
