package com.activitylock.app.ui.screens

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
import com.activitylock.app.ui.components.UnlockVerifyDialog

/**
 * 解锁验证全局设置。
 * 防绕过原则：验证开启期间，任何削弱性修改（关闭、换方式、改密码、缩短等待）
 * 每一次都必须当场通过"最强验证"（全局 + 各界面单独条件的最大值），
 * 不存在"本次会话已验证"的通行证——否则等待 5 秒就能关掉整个验证体系。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifySettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(VerifyGate.isEnabled(ctx)) }
    var method by remember { mutableStateOf(VerifyGate.method(ctx)) }
    var password by remember { mutableStateOf(VerifyGate.password(ctx)) }
    var delaySec by remember { mutableStateOf(VerifyGate.delaySeconds(ctx)) }
    var quizCount by remember { mutableStateOf(VerifyGate.quizCount(ctx)) }
    var randomDigits by remember { mutableStateOf(VerifyGate.randomDigits(ctx)) }

    // 削弱性修改：逐次验证，无会话通行证
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun requireVerify(action: () -> Unit) {
        val req = VerifyGate.bulkRequirement(ctx, AppStore.rules)
        if (!req.isNone) pendingAction = action else action()
    }

    fun persist() {
        VerifyGate.save(ctx, enabled, method, password, delaySec, quizCount)
        VerifyGate.setRandomDigits(ctx, randomDigits)
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
                                persist()
                            } else {
                                requireVerify {
                                    enabled = false
                                    persist()
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
                        Text("验证方式", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = method == VerifyGate.METHOD_DELAY,
                                onClick = {
                                    requireVerify { method = VerifyGate.METHOD_DELAY; persist() }
                                }
                            )
                            Text("等待指定时间")
                        }
                        if (method == VerifyGate.METHOD_DELAY) {
                            Row(
                                Modifier.padding(start = 40.dp, top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("等待", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = delaySec.toString(),
                                    onValueChange = { raw ->
                                        raw.filter { c -> c.isDigit() }.take(4).toIntOrNull()?.let {
                                            delaySec = it.coerceIn(5, 3600)
                                        }
                                    },
                                    suffix = { Text("秒") },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium
                                        .copy(fontFeatureSettings = "tnum", textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                                    modifier = Modifier.width(96.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = method == VerifyGate.METHOD_QUIZ,
                                onClick = {
                                    requireVerify { method = VerifyGate.METHOD_QUIZ; persist() }
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
                                    value = quizCount.toString(),
                                    onValueChange = { raw ->
                                        raw.filter { c -> c.isDigit() }.take(2).toIntOrNull()?.let {
                                            quizCount = it.coerceIn(1, 10)
                                        }
                                    },
                                    suffix = { Text("题") },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium
                                        .copy(fontFeatureSettings = "tnum", textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                                    modifier = Modifier.width(80.dp)
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = method == VerifyGate.METHOD_RANDOM,
                                onClick = {
                                    requireVerify { method = VerifyGate.METHOD_RANDOM; persist() }
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
                                    value = randomDigits.toString(),
                                    onValueChange = { raw ->
                                        raw.filter { c -> c.isDigit() }.take(2).toIntOrNull()?.let {
                                            randomDigits = it.coerceIn(4, 12)
                                        }
                                    },
                                    suffix = { Text("位") },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium
                                        .copy(fontFeatureSettings = "tnum", textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                                    modifier = Modifier.width(80.dp)
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = method == VerifyGate.METHOD_PASSWORD,
                                onClick = {
                                    requireVerify { method = VerifyGate.METHOD_PASSWORD; persist() }
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
                                onClick = { requireVerify { persist() } }
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
            onConfirmed = {
                pendingAction = null
                action()
            },
            onDismiss = { pendingAction = null }
        )
    }
}
