package com.activitylock.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AmsController
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.LockEngine
import com.activitylock.app.core.VerifyGate
import com.activitylock.app.ui.theme.ActivityLockTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch

/**
 * 磁贴「全部解锁」的自律验证页：透明背景 + 居中验证卡片。
 * 验证条件 = 全局 + 各界面单独条件的最强要求（要密码就要密码，否则取最长等待），
 * 防止给某界面单独设了密码却被全局倒计时放过。
 * 验证通过后执行全部解锁并关闭。
 */
class UnlockGateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStore.ensureInit(applicationContext)
        val requirement = if (VerifyGate.outsideUnlockWindow(applicationContext))
            VerifyGate.Requirement("window_blocked", 0, emptyList())
        else VerifyGate.bulkRequirement(applicationContext, AppStore.rules)
        val windowDesc = VerifyGate.unlockWindowDesc(applicationContext)
        setContent {
            ActivityLockTheme {
                Surface(
                    Modifier.fillMaxSize(),
                    color = Color(0x66000000)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        GateCard(
                            requirement = requirement,
                            windowDesc = windowDesc,
                            onConfirmed = {
                                Thread {
                                    runBlocking {
                                        for (r in AppStore.rules) {
                                            if (r.locked) {
                                                LockEngine.apply(r.packageName, r.activityName, false)
                                            }
                                        }
                                    }
                                    AmsController.writeRules()
                                }.start()
                                finish()
                            },
                            onDismiss = { finish() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GateCard(
    requirement: VerifyGate.Requirement,
    windowDesc: String,
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit,
) {
    var passed by remember { mutableStateOf(requirement.isNone) }

    Card(Modifier.width(320.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("全部解锁验证", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            when (requirement.mode) {
                VerifyGate.METHOD_RANDOM -> {
                    var target by remember { mutableStateOf(VerifyGate.genRandomCode(requirement.randomDigits)) }
                    var input by remember { mutableStateOf("") }
                    val ok = input == target
                    Text("照抄随机密码后全部解锁：", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        target,
                        style = MaterialTheme.typography.displaySmall
                            .copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("照抄上方密码") },
                        singleLine = true
                    )
                    if (input.isNotBlank() && !ok) {
                        Spacer(Modifier.height(4.dp))
                        Text("不一致，请照抄", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                    passed = ok
                }
                VerifyGate.METHOD_QUIZ -> {
                    // 连答制：与全局验证对话框一致（题数取最强要求，防止改了题数这里只答一道就过）
                    var solved by remember { mutableIntStateOf(0) }
                    var question by remember { mutableStateOf(VerifyGate.genQuiz()) }
                    var input by remember { mutableStateOf("") }
                    val total = requirement.quizCount.coerceAtLeast(1)
                    Text(
                        "第 " + minOf(solved + 1, total) + " / " + total + " 题",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("答对 $total 道口算题即可全部解锁：", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(question.first, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.filter { c -> c.isDigit() || c == '-' } },
                        placeholder = { Text("答案") },
                        singleLine = true
                    )
                    // 答对自动进入下一题；全部答对后停止出新题
                    LaunchedEffect(input) {
                        if (solved < total && input.isNotBlank() && input.trim().toIntOrNull() == question.second) {
                            solved++
                            input = ""
                            if (solved < total) question = VerifyGate.genQuiz()
                        }
                    }
                    passed = solved >= total
                }
                VerifyGate.METHOD_PASSWORD -> {
                    var input by remember { mutableStateOf("") }
                    val ok = requirement.passwordOk(input)
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("解锁密码") },
                        singleLine = true
                    )
                    if (input.isNotBlank() && !ok) {
                        Spacer(Modifier.height(4.dp))
                        Text("密码不正确", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                    passed = ok
                }
                VerifyGate.METHOD_DELAY -> {
                    val total = requirement.delaySec.coerceAtLeast(1)
                    var remaining by remember { mutableIntStateOf(total) }
                    LaunchedEffect(Unit) {
                        while (remaining > 0) {
                            delay(1000)
                            remaining--
                        }
                    }
                    Text(
                        if (remaining > 0) "自律提醒：请等待 $remaining 秒后再全部解锁。"
                        else "等待完成，可以全部解锁了。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    passed = remaining <= 0
                }
                "window_blocked" -> {
                    Text(
                        "已设置解锁时间范围 " + windowDesc +
                            "，当前时段无法全部解锁。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    passed = false
                }
                else -> {
                    Text("没有需要验证的界面。", style = MaterialTheme.typography.bodyMedium)
                    passed = true
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                TextButton(enabled = passed, onClick = onConfirmed) {
                    Text(if (passed) "确认全部解锁" else "请等待…")
                }
            }
        }
    }
}
