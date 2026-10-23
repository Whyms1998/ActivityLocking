package com.activitylock.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.VerifyGate
import com.activitylock.app.ui.components.DurationField
import com.activitylock.app.ui.components.UnlockVerifyDialog
import kotlinx.coroutines.delay

/**
 * 解锁验证全局设置。
 * 防绕过原则：验证开启期间，任何削弱性修改（关闭、换方式、改密码、缩短等待）
 * 每一次都必须当场通过"最强验证"（全局 + 各界面单独条件的最大值），
 * 不存在"本次会话已验证"的通行证——否则等待 5 秒就能关掉整个验证体系。
 */
private fun hmText(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

private fun parseHm(text: String): Int? {
    val m = Regex("^([0-9]{1,2}):([0-9]{2})$").find(text.trim()) ?: return null
    val h = m.groupValues[1].toIntOrNull() ?: return null
    val mm = m.groupValues[2].toIntOrNull() ?: return null
    if (h !in 0..23 || mm !in 0..59) return null
    return h * 60 + mm
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifySettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(VerifyGate.isEnabled(ctx)) }
    var method by remember { mutableStateOf(VerifyGate.method(ctx)) }
    var password by remember { mutableStateOf(VerifyGate.password(ctx)) }
    // ★ v1.24.9：等待时长拆成「已保存值」与「编辑中的草稿值」。
    //   输入框统一为 [DurationField]（秒 / 分钟 / 小时，**无范围限制**）。
    //   草稿必须点「保存」才落盘 —— 见 [persist] 的注释（堵住未验证削弱值被顺手写盘）。
    var delaySec by remember { mutableStateOf(VerifyGate.delaySeconds(ctx)) }
    var delayDraftSec by remember { mutableStateOf(VerifyGate.delaySeconds(ctx)) }
    var quizCount by remember { mutableStateOf(VerifyGate.quizCount(ctx)) }
    var quizText by remember { mutableStateOf(VerifyGate.quizCount(ctx).toString()) }
    var randomDigits by remember { mutableStateOf(VerifyGate.randomDigits(ctx)) }
    var digitsText by remember { mutableStateOf(VerifyGate.randomDigits(ctx).toString()) }
    var windowEnabled by remember { mutableStateOf(VerifyGate.unlockWindowEnabled(ctx)) }
    var windowStartText by remember { mutableStateOf(hmText(VerifyGate.unlockWindowStart(ctx))) }
    var windowEndText by remember { mutableStateOf(hmText(VerifyGate.unlockWindowEnd(ctx))) }
    // 已落盘的时段（分钟）。关闭时间范围时用它写回，避免读到被改动的输入框内容。
    var savedWindowStart by remember { mutableStateOf(VerifyGate.unlockWindowStart(ctx)) }
    var savedWindowEnd by remember { mutableStateOf(VerifyGate.unlockWindowEnd(ctx)) }

    // 削弱性修改：逐次验证，无会话通行证
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // 统一「保存结果」反馈：任何落盘都给出可见提示，避免用户「以为改了」
    var feedback by remember { mutableStateOf<String?>(null) }

    fun requireVerify(action: () -> Unit) {
        val req = VerifyGate.bulkRequirement(ctx, AppStore.rules)
        if (!req.isNone) pendingAction = action else action()
    }

    /**
     * 把当前界面上的全部字段一次性落盘。
     * ★ 修复：原实现只写 enabled/method/password/delaySec/quizCount，
     * 且输入框改动完全不触发保存（改了不生效且无反馈）。
     * ★ 数值一律从「草稿文本」解析，非法或为空时回退到已保存值，
     * 避免切方式时用空草稿把配置写坏。
     */
    fun persist() {
        // ★ v1.24.9 修复：只落盘**已提交**的值，**不再读输入框草稿**。
        //   旧实现把 delayText / quizText / digitsText 直接解析落盘，于是存在这条绕过路径：
        //   把等待从 600 秒改成 5 秒 → 不点「保存」→ 顺手切一下验证方式 /
        //   关一下总开关 → persist() 把 5 秒写盘，而「削弱需验证」根本没触发。
        //   现在草稿必须走各自那枚「保存」按钮（内部会判 weaken 并当场验证）才生效。
        VerifyGate.save(ctx, enabled, method, password, delaySec, quizCount)
        VerifyGate.setRandomDigits(ctx, randomDigits)
    }

    /** 落盘 + 反馈。所有保存路径统一走这里。 */
    fun persistWithFeedback(reason: String) {
        persist()
        feedback = reason
    }

    // 反馈：显示一次并自动清除（用 key 变化触发，避免重复 toast）
    LaunchedEffect(feedback) {
        feedback?.let {
            AppStore.toast(it)
            delay(1500)
            feedback = null
        }
    }

    /**
     * 提交一个「数值型」设置（等待秒数 / 题数 / 位数）。
     * ★ 安全：这些改动若使门槛降低（秒数变小、题数变小、位数变小）属于削弱性修改，
     * 必须走 requireVerify 当场验证；提高门槛则直接保存。
     * ★ 校验：草稿为空或非法 → 明确报错并回滚，不再静默钳制。
     */
    fun submitNumeric(
        raw: String,
        oldVal: Int,
        min: Int,
        max: Int,
        unitLabel: String,
        onOk: (Int) -> Unit,
        onRollback: () -> Unit
    ) {
        val v = raw.trim().toIntOrNull()
        if (v == null || v < min || v > max) {
            AppStore.toast("请输入 $min-$max 之间的整数")
            onRollback()
            return
        }
        val weaker = v < oldVal
        val commit = {
            onOk(v)
            persistWithFeedback("已保存：$v$unitLabel")
        }
        if (weaker) requireVerify(commit) else commit()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("解锁验证", style = MaterialTheme.typography.titleMedium) },
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
            if (enabled) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "验证开启中：关闭验证、切换方式、修改密码、缩短等待，" +
                            "每一次都需要当场通过最强验证（含各界面单独设置的密码与答题）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("启用解锁验证", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "解锁已锁定的界面时，需要先通过验证",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { want ->
                            if (want) {
                                enabled = true
                                persistWithFeedback("已开启解锁验证")
                            } else {
                                requireVerify {
                                    enabled = false
                                    persistWithFeedback("已关闭解锁验证")
                                }
                            }
                        }
                    )
                }
            }

            if (enabled) {
                Spacer(Modifier.height(16.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("解锁时间范围", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "只在设定时段内允许解锁（支持跨午夜，如 22:00-07:00），其余时间验证一律不通过",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = windowStartText,
                                onValueChange = { windowStartText = it },
                                label = { Text("开始") },
                                placeholder = { Text("22:00") },
                                singleLine = true,
                                enabled = windowEnabled,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(12.dp))
                            OutlinedTextField(
                                value = windowEndText,
                                onValueChange = { windowEndText = it },
                                label = { Text("结束") },
                                placeholder = { Text("07:00") },
                                singleLine = true,
                                enabled = windowEnabled,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        // ★ 修复：用户反馈「没有开关按钮」——原实现只有「保存/关闭时间范围限制」
                        // 两个文字按钮，语义不直观。现在改为明确的 Switch 开关 + 保存按钮。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("启用时段限制", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (windowEnabled)
                                        "已启用：${hmText(savedWindowStart)}-${hmText(savedWindowEnd)}"
                                    else "未启用：全天允许解锁",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (windowEnabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = windowEnabled,
                                onCheckedChange = { want ->
                                    if (!want) {
                                        // 关闭属削弱性修改 → 当场验证
                                        requireVerify {
                                            windowEnabled = false
                                            VerifyGate.saveUnlockWindow(ctx, false, savedWindowStart, savedWindowEnd)
                                            feedback = "已关闭时段限制"
                                        }
                                    } else {
                                        // 开启需先有合法时段，否则提示用户填时间后点保存
                                        val s = parseHm(windowStartText)
                                        val e = parseHm(windowEndText)
                                        if (s == null || e == null) {
                                            AppStore.toast("请先填写时间（HH:mm，如 22:00 / 07:00），再点「保存时段」")
                                        } else {
                                            requireVerify {
                                                windowEnabled = true
                                                savedWindowStart = s
                                                savedWindowEnd = e
                                                VerifyGate.saveUnlockWindow(ctx, true, s, e)
                                                feedback = "已启用：${hmText(s)}-${hmText(e)}"
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        TextButton(
                            enabled = windowEnabled,
                            onClick = {
                                val s = parseHm(windowStartText)
                                val e = parseHm(windowEndText)
                                if (s == null || e == null) {
                                    AppStore.toast("时间格式应为 HH:mm，如 22:00")
                                } else {
                                    requireVerify {
                                        savedWindowStart = s
                                        savedWindowEnd = e
                                        VerifyGate.saveUnlockWindow(ctx, windowEnabled, s, e)
                                        feedback = "时段已保存：${hmText(s)}-${hmText(e)}"
                                    }
                                }
                            }
                        ) { Text("保存时段") }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("验证方式", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = method == VerifyGate.METHOD_DELAY,
                                onClick = {
                                    requireVerify {
                                        method = VerifyGate.METHOD_DELAY
                                        persistWithFeedback("验证方式：等待指定时间")
                                    }
                                }
                            )
                            Text("等待指定时间")
                        }
                        if (method == VerifyGate.METHOD_DELAY) {
                            // ★ v1.24.9：整块换成全项目统一的 [DurationField]。
                            //   旧实现是「只有秒 + 范围 5–3600」的输入框：想设「等 2 小时」
                            //   只能填 7200，填 9999 还会被标红。用户原话：
                            //   「不需要范围设定在 5～3600 秒，应该也是秒、分、时可以自由选择输入。」
                            Box(Modifier.padding(start = 40.dp, top = 4.dp, end = 8.dp)) {
                                DurationField(
                                    seconds = delayDraftSec,
                                    onChange = { delayDraftSec = it },
                                    equivalent = { sec -> "解锁前需等待 " + relockDelayLabel(sec) },
                                )
                            }
                            Row(
                                Modifier.padding(start = 40.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = {
                                    val v = VerifyGate.safeDelaySec(delayDraftSec)
                                    val commit = {
                                        delaySec = v
                                        delayDraftSec = v
                                        persistWithFeedback("已保存：等待 " + relockDelayLabel(v))
                                    }
                                    // 缩短等待 = 削弱门槛 → 当场验证；加长直接生效
                                    if (v < VerifyGate.delaySeconds(ctx)) requireVerify(commit) else commit()
                                }) { Text("保存") }
                                Spacer(Modifier.width(8.dp))
                                // ★ 保存反馈（用户反复提过「改完不知道有没有生效」）
                                Text(
                                    if (delayDraftSec != delaySec) "尚未保存：点「保存」后生效"
                                    else "等待时长已保存",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (delayDraftSec != delaySec) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = method == VerifyGate.METHOD_QUIZ,
                                onClick = {
                                    requireVerify {
                                        method = VerifyGate.METHOD_QUIZ
                                        persistWithFeedback("验证方式：答对若干道口算题")
                                    }
                                }
                            )
                            Text("答对若干道口算题")
                        }
                        if (method == VerifyGate.METHOD_QUIZ) {
                            Row(
                                Modifier.padding(start = 40.dp, top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("连对", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = quizText,
                                    onValueChange = { raw ->
                                        quizText = raw.filter { c -> c.isDigit() }.take(2)
                                    },
                                    suffix = { Text("题") },
                                    singleLine = true,
                                    placeholder = { Text("1-10") },
                                    isError = quizText.isNotEmpty() && quizText.toIntOrNull()?.let { it !in 1..10 } == true,
                                    // ★ v1.24.7 统一：范围从 placeholder 提到常驻文案，
                                    //   并在越界时说清「只能填」而不是让用户猜。
                                    supportingText = {
                                        Text(
                                            if (quizText.isNotEmpty() && quizText.toIntOrNull()?.let { it !in 1..10 } == true)
                                                "只能填 1 – 10 题" else "范围 1 – 10 题",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (quizText.isNotEmpty() && quizText.toIntOrNull()?.let { it !in 1..10 } == true)
                                                MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    textStyle = MaterialTheme.typography.bodyMedium
                                        .copy(fontFeatureSettings = "tnum", textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                                    modifier = Modifier.width(80.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = {
                                    submitNumeric(
                                        raw = quizText,
                                        oldVal = VerifyGate.quizCount(ctx),
                                        min = 1,
                                        max = 10,
                                        unitLabel = " 题",
                                        onOk = { v ->
                                            quizCount = v
                                            quizText = v.toString()
                                            persist()
                                        },
                                        onRollback = { quizText = VerifyGate.quizCount(ctx).toString() }
                                    )
                                }) { Text("保存") }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = method == VerifyGate.METHOD_RANDOM,
                                onClick = {
                                    requireVerify {
                                        method = VerifyGate.METHOD_RANDOM
                                        persistWithFeedback("验证方式：照抄随机密码")
                                    }
                                }
                            )
                            Text("照抄随机密码")
                        }
                        if (method == VerifyGate.METHOD_RANDOM) {
                            Row(
                                Modifier.padding(start = 40.dp, top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("位数", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = digitsText,
                                    onValueChange = { raw ->
                                        digitsText = raw.filter { c -> c.isDigit() }.take(2)
                                    },
                                    suffix = { Text("位") },
                                    singleLine = true,
                                    placeholder = { Text("4-12") },
                                    isError = digitsText.isNotEmpty() && digitsText.toIntOrNull()?.let { it !in 4..12 } == true,
                                    // ★ v1.24.7 统一：同上面两处，常驻范围文案 + 越界明示
                                    supportingText = {
                                        Text(
                                            if (digitsText.isNotEmpty() && digitsText.toIntOrNull()?.let { it !in 4..12 } == true)
                                                "只能填 4 – 12 位" else "范围 4 – 12 位",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (digitsText.isNotEmpty() && digitsText.toIntOrNull()?.let { it !in 4..12 } == true)
                                                MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    textStyle = MaterialTheme.typography.bodyMedium
                                        .copy(fontFeatureSettings = "tnum", textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                                    modifier = Modifier.width(80.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = {
                                    submitNumeric(
                                        raw = digitsText,
                                        oldVal = VerifyGate.randomDigits(ctx),
                                        min = 4,
                                        max = 12,
                                        unitLabel = " 位",
                                        onOk = { v ->
                                            randomDigits = v
                                            digitsText = v.toString()
                                            persist()
                                        },
                                        onRollback = { digitsText = VerifyGate.randomDigits(ctx).toString() }
                                    )
                                }) { Text("保存") }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = method == VerifyGate.METHOD_PASSWORD,
                                onClick = {
                                    requireVerify {
                                        method = VerifyGate.METHOD_PASSWORD
                                        persistWithFeedback("验证方式：输入指定密码")
                                    }
                                }
                            )
                            Text("输入指定密码")
                        }
                        if (method == VerifyGate.METHOD_PASSWORD) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 40.dp, top = 8.dp, end = 16.dp),
                                enabled = true,
                                placeholder = { Text("设置解锁密码") },
                                singleLine = true
                            )
                            TextButton(
                                enabled = password.isNotBlank(),
                                onClick = {
                                    requireVerify {
                                        persistWithFeedback("密码已保存")
                                    }
                                }
                            ) { Text("保存密码") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "验证在收藏夹解锁、删除和磁贴「全部解锁」时生效；锁定操作不受影响。\n" +
                    "每个界面可以在收藏夹「编辑」里单独设置解锁条件（等待倒计时 / 答题 / 随机密码 / 专属密码），" +
                    "单独设置优先于全局；磁贴全部解锁会取所有界面的最强条件。\n" +
                    "降低任何门槛的修改（换方式、减题数、缩位数等）都会要求当场验证。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    pendingAction?.let { action ->
        UnlockVerifyDialog(
            requirement = VerifyGate.bulkRequirement(ctx, AppStore.rules),
            title = "修改验证",
            hint = "验证开启期间，削弱性修改需当场通过最强验证",
            enforceWindow = false,
            onConfirmed = {
                pendingAction = null
                action()
            },
            onDismiss = { pendingAction = null }
        )
    }
}
