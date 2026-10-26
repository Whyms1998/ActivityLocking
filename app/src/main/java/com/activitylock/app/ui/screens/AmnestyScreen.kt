package com.activitylock.app.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AmnestyStore
import com.activitylock.app.core.AmsController
import com.activitylock.app.core.LockStrength
import com.activitylock.app.core.ScheduleEngine
import com.activitylock.app.core.UnlockExemptions
import com.activitylock.app.core.VerifyGate
import com.activitylock.app.ui.components.DurationField
import com.activitylock.app.ui.components.DurationPickerDialog
import com.activitylock.app.ui.components.UnlockVerifyDialog

/**
 * 特赦通行证（v1.29.0 新增）。
 *
 * 设计意图见 [AmnestyStore] 的类注释。这个页面只做三件事：
 *  1. **说清楚还剩多少** —— 剩余次数就是全部的约束力来源，必须一眼看到；
 *  2. **申请一次** —— 走特赦自己的准入（与日常解锁完全独立），扣一次额度；
 *  3. **改规则** —— 全部可自定义，但改规则本身属于「削弱自律」，
 *     所以要先过**日常解锁验证**。设门槛比用门槛难，这个不对称是故意的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmnestyScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current

    var enabled by remember { mutableStateOf(AmnestyStore.enabled(ctx)) }
    var durationSec by remember { mutableStateOf(AmnestyStore.durationSec(ctx)) }
    var period by remember { mutableStateOf(AmnestyStore.period(ctx)) }
    var limit by remember { mutableStateOf(AmnestyStore.limit(ctx)) }
    var verifyMode by remember { mutableStateOf(AmnestyStore.verifyMode(ctx)) }
    var delaySec by remember { mutableStateOf(AmnestyStore.verifyDelaySec(ctx)) }
    var quizCount by remember { mutableStateOf(AmnestyStore.verifyQuizCount(ctx)) }
    var randomDigits by remember { mutableStateOf(AmnestyStore.verifyRandomDigits(ctx)) }
    var escalate by remember { mutableStateOf(AmnestyStore.escalate(ctx)) }
    var shrink by remember { mutableStateOf(AmnestyStore.shrink(ctx)) }

    /** 本周期已用次数：每次申请后刷新，页面上的大数字立刻变。 */
    var used by remember { mutableIntStateOf(AmnestyStore.used(ctx)) }
    val remaining = (limit - used).coerceAtLeast(0)

    // ★ 以下全部是「草稿 + 保存」：与 VerifySettingsScreen 同一套口径。
    //   用户原话「次数上限应该可以自定义，准入条件那里所有的条件也应该可以自定义，
    //   按照原来解锁验证里那样子」—— 原页就是 chip 快捷 + 自由输入框 + 保存按钮。
    //   绝不 onValueChange 即时落盘：输入 "15" 的过程中会先落成 1，属于静默改写。
    var limitText by remember { mutableStateOf(limit.toString()) }
    var delayDraft by remember { mutableStateOf(delaySec) }
    var quizText by remember { mutableStateOf(quizCount.toString()) }
    var digitsText by remember { mutableStateOf(randomDigits.toString()) }
    var pwdText by remember { mutableStateOf(AmnestyStore.verifyPassword(ctx)) }

    var showDurationPicker by remember { mutableStateOf(false) }
    /** 本次进页面是否已通过日常验证（改参数的门槛）。 */
    var editing by remember { mutableStateOf(false) }
    var showGuard by remember { mutableStateOf(false) }
    /** 本次改参数要过的那一档（日常验证与当前特赦准入取更严的），进入前算好。 */
    var guardReq by remember { mutableStateOf<VerifyGate.Requirement?>(null) }
    var guardAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    /** 特赦申请弹窗。 */
    var showApply by remember { mutableStateOf(false) }

    // 计划生效期间是否禁用（与 TempUnlockActivity / 磁贴同一判据）
    val planBlocked = LockStrength.noExemptInSchedule(ctx) &&
        ScheduleEngine.anyActive(com.activitylock.app.core.AppStore.schedules)

    /** 改参数前的统一闸门：没过验证就先弹验证，通过了再执行这次修改。 */
    fun guard(block: () -> Unit) {
        if (editing) {
            block()
            return
        }
        // ★ v1.29.1：门槛 = 日常验证 / 当前特赦准入 里更严的那一档。
        //   旧实现只看日常验证，于是「把特赦从密码降成等待」零成本，
        //   特赦的强度可以被一层层往下削。
        val gate = AmnestyStore.editRequirement(ctx)
        // 两档都是 none（日常验证关着且特赦准入门槛为空）→ 不该弹空弹窗，视同已通过
        if (gate.isNone) {
            editing = true
            block()
            return
        }
        guardReq = gate
        guardAction = block
        showGuard = true
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("特赦通行证", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {

            // ---------------- 状态 + 申请 ----------------
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("启用特赦", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Switch(
                                checked = enabled,
                                onCheckedChange = { v ->
                                    guard {
                                        enabled = v
                                        AmnestyStore.setEnabled(ctx, v)
                                    }
                                }
                            )
                        }

                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "$remaining",
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Bold,
                                // 未启用时用灰字：否则蓝色大数字会让人以为"现在能用 2 次"，
                                // 但按钮其实是灰的——这就是最招人烦的那种不一致。
                                color = when {
                                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                                    remaining > 0 -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.error
                                }
                            )
                            Spacer(Modifier.height(1.dp))
                            Text(
                                " / $limit 次 · " + if (period == AmnestyStore.PERIOD_WEEK) "本周" else "今天",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp, start = 6.dp)
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "本次可放行 " + relockDelayLabel(AmnestyStore.durationForNext(ctx)) +
                                " · 准入：" + AmnestyStore.verifySummary(ctx),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(12.dp))
                        val canApply = enabled && remaining > 0 && !planBlocked
                        Button(
                            enabled = canApply,
                            onClick = { showApply = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                when {
                                    planBlocked -> "计划生效中，暂不可申请"
                                    !enabled -> "特赦未启用"
                                    remaining <= 0 -> "本周期次数已用完"
                                    else -> "申请特赦 · " +
                                        relockDelayLabel(AmnestyStore.durationForNext(ctx))
                                }
                            )
                        }
                        if (AmnestyStore.lastUsedAt(ctx) > 0L) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "上次使用：" + formatAt(AmnestyStore.lastUsedAt(ctx)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ---------------- 配额 ----------------
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("配额", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))

                        Text("周期", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = period == AmnestyStore.PERIOD_WEEK,
                                onClick = {
                                    guard {
                                        period = AmnestyStore.PERIOD_WEEK
                                        AmnestyStore.setPeriod(ctx, AmnestyStore.PERIOD_WEEK)
                                        used = AmnestyStore.used(ctx)
                                    }
                                },
                                label = { Text("每周") }
                            )
                            FilterChip(
                                selected = period == AmnestyStore.PERIOD_DAY,
                                onClick = {
                                    guard {
                                        period = AmnestyStore.PERIOD_DAY
                                        AmnestyStore.setPeriod(ctx, AmnestyStore.PERIOD_DAY)
                                        used = AmnestyStore.used(ctx)
                                    }
                                },
                                label = { Text("每天") }
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Text("次数上限", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        // ★ 快捷档已删除：既然能自由填，1/2/3 只是让界面更挤。
                        //   用户原话「如果自定义的话，那么默认的 1、2、3 次就不需要了」。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = limitText,
                                // ★ 只过滤非数字 + 限 3 位，**不在输入过程中 coerce**
                                onValueChange = { raw -> limitText = raw.filter { it.isDigit() }.take(3) },
                                singleLine = true,
                                suffix = { Text("次") },
                                placeholder = { Text("自定义") },
                                isError = limitText.isNotEmpty() &&
                                    (limitText.toIntOrNull()?.let { it !in 0..999 } ?: true),
                                textStyle = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(150.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
                                val v = limitText.toIntOrNull()
                                if (v != null && v in 0..999) {
                                    guard {
                                        limit = v
                                        AmnestyStore.setLimit(ctx, v)
                                        used = AmnestyStore.used(ctx)
                                    }
                                }
                            }) { Text("保存") }
                        }
                        // ★ 越界只靠 isError 红框 + 保存时拦截，不再常驻范围文案
                        //   （用户要求「准入条件和其他需要输入时间和数字的地方，
                        //   建议和说明都不需要了」）。

                        Spacer(Modifier.height(12.dp))
                        Text("单次放行时长", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(300, 600, 900).forEach { v ->
                                FilterChip(
                                    selected = durationSec == v,
                                    onClick = {
                                        guard {
                                            durationSec = v
                                            AmnestyStore.setDurationSec(ctx, v)
                                        }
                                    },
                                    label = { Text("${v / 60} 分") }
                                )
                            }
                            FilterChip(
                                selected = durationSec !in listOf(300, 600, 900),
                                onClick = { guard { showDurationPicker = true } },
                                label = { Text("自定义") }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "当前 " + relockDelayLabel(durationSec),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // ---------------- 准入条件 ----------------
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("准入条件", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                VerifyGate.METHOD_DELAY to "等待",
                                VerifyGate.METHOD_QUIZ to "口算",
                                VerifyGate.METHOD_RANDOM to "照抄",
                                VerifyGate.METHOD_PASSWORD to "密码",
                            ).forEach { (m, label) ->
                                FilterChip(
                                    selected = verifyMode == m,
                                    onClick = {
                                        guard {
                                            verifyMode = m
                                            AmnestyStore.setVerifyMode(ctx, m)
                                        }
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }

                        when (verifyMode) {
                            VerifyGate.METHOD_DELAY -> {
                                Spacer(Modifier.height(10.dp))
                                Text("等待时长", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(4.dp))
                                // ★ 与「解锁验证」页完全一致：统一 DurationField（秒 / 分 / 时自由输入，
                                //   不做业务范围限制），配「保存」按钮 —— 输入过程中不落盘。
                                DurationField(
                                    seconds = delayDraft,
                                    onChange = { delayDraft = it },
                                    equivalent = { sec -> "基础等待 " + relockDelayLabel(sec) },
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = {
                                        guard {
                                            delaySec = delayDraft
                                            AmnestyStore.setVerifyDelaySec(ctx, delayDraft)
                                        }
                                    }) { Text("保存") }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (delayDraft != delaySec) "尚未保存" else "已保存",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (delayDraft != delaySec) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                            VerifyGate.METHOD_QUIZ -> {
                                Spacer(Modifier.height(10.dp))
                                Text("题数", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = quizText,
                                        onValueChange = { raw -> quizText = raw.filter { it.isDigit() }.take(3) },
                                        singleLine = true,
                                        suffix = { Text("题") },
                                        placeholder = { Text("3") },
                                        isError = quizText.isNotEmpty() &&
                                            (quizText.toIntOrNull()?.let { it !in 1..999 } ?: true),
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.width(130.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = {
                                        val v = quizText.toIntOrNull()
                                        if (v != null && v in 1..999) {
                                            guard {
                                                quizCount = v
                                                AmnestyStore.setVerifyQuizCount(ctx, v)
                                            }
                                        }
                                    }) { Text("保存") }
                                }
                            }
                            VerifyGate.METHOD_RANDOM -> {
                                Spacer(Modifier.height(10.dp))
                                Text("位数", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = digitsText,
                                        onValueChange = { raw -> digitsText = raw.filter { it.isDigit() }.take(3) },
                                        singleLine = true,
                                        suffix = { Text("位") },
                                        placeholder = { Text("6") },
                                        isError = digitsText.isNotEmpty() &&
                                            (digitsText.toIntOrNull()?.let { it !in 1..999 } ?: true),
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.width(130.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = {
                                        val v = digitsText.toIntOrNull()
                                        if (v != null && v in 1..999) {
                                            guard {
                                                randomDigits = v
                                                AmnestyStore.setVerifyRandomDigits(ctx, v)
                                            }
                                        }
                                    }) { Text("保存") }
                                }
                            }
                            VerifyGate.METHOD_PASSWORD -> {
                                Spacer(Modifier.height(10.dp))
                                Text("特赦密码", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = pwdText,
                                        // 密码不做长度限制：它和题数/位数不同，
                                        // 没有"越长越难抄"的成本，限长只会逼用户改用短密码。
                                        onValueChange = { pwdText = it },
                                        singleLine = true,
                                        placeholder = { Text("设置一个密码") },
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = {
                                        guard { AmnestyStore.setVerifyPassword(ctx, pwdText) }
                                    }) { Text("保存") }
                                }
                            }
                        }
                    }
                }
            }

            // ---------------- 递增 ----------------
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("递增控制", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "越用越难",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = escalate,
                                onCheckedChange = {
                                    guard {
                                        escalate = it
                                        AmnestyStore.setEscalate(ctx, it)
                                    }
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "越用越短",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = shrink,
                                onCheckedChange = {
                                    guard {
                                        shrink = it
                                        AmnestyStore.setShrink(ctx, it)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (editing) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已通过验证，可调整以上参数",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                }
            } else {
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showDurationPicker) {
        DurationPickerDialog(
            title = "单次放行时长",
            initialSec = durationSec,
            onDismiss = { showDurationPicker = false },
            onConfirm = { sec ->
                durationSec = sec
                AmnestyStore.setDurationSec(ctx, sec)
                showDurationPicker = false
            }
        )
    }

    // 改参数前的验证
    if (showGuard) {
        UnlockVerifyDialog(
            requirement = guardReq,
            title = "修改特赦规则",
            hint = "通过后，本次进入本页即可自由调整参数",
            enforceWindow = false,
            onConfirmed = {
                showGuard = false
                editing = true
                guardAction?.invoke()
                guardAction = null
            },
            onDismiss = {
                showGuard = false
                guardAction = null
            }
        )
    }

    // 特赦申请：走特赦自己的准入，通过后放行 + 扣一次
    if (showApply) {
        UnlockVerifyDialog(
            requirement = AmnestyStore.requirementForNext(ctx),
            title = "申请特赦",
            hint = "通过后放行全部已锁界面 " +
                relockDelayLabel(AmnestyStore.durationForNext(ctx)) +
                "，并计入本周期次数，到期自动落锁",
            onConfirmed = {
                val sec = AmnestyStore.durationForNext(ctx)
                UnlockExemptions.addGlobal(ctx, System.currentTimeMillis() + sec * 1000L)
                AmnestyStore.record(ctx)
                AmsController.writeRules()
                used = AmnestyStore.used(ctx)
                showApply = false
                // 放行后直接把用户送回桌面 —— 特赦的下一步一定是"去用那个 App"，
                // 停在设置页等于让他再多点两下，白给的摩擦。
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_MAIN)
                            .addCategory(android.content.Intent.CATEGORY_HOME)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            onDismiss = { showApply = false }
        )
    }
}

/** 时间戳 → "10-26 20:15"，只用于"上次使用"一行。 */
private fun formatAt(ms: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ms))
