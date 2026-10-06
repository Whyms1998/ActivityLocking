package com.activitylock.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.activitylock.app.core.VerifyGate
import kotlinx.coroutines.delay

/**
 * 解锁验证：全屏页面（严肃时刻的仪式感），按给定条件展示答题/倒计时/密码。
 * requirement 为空时使用全局配置；条件为 none 时调用方不应弹出本对话框。
 * hint = 后果说明（告诉用户通过验证后会发生什么）。
 */
@Composable
fun UnlockVerifyDialog(
    requirement: VerifyGate.Requirement? = null,
    title: String = "解锁验证",
    hint: String = "自律提醒",
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val req = requirement ?: VerifyGate.globalRequirement(ctx)
    val tnum = TextStyle(fontFeatureSettings = "tnum")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 48.dp)
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(20.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(40.dp))

                when (req.mode) {
                    VerifyGate.METHOD_QUIZ -> {
                        var solved by remember { mutableIntStateOf(0) }
                        var question by remember { mutableStateOf(VerifyGate.genQuiz()) }
                        var input by remember { mutableStateOf("") }
                        val total = req.quizCount.coerceAtLeast(1)
                        val ok = solved >= total
                        Text(
                            "第 " + (solved + 1) + " / " + total + " 题",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            question.first,
                            style = MaterialTheme.typography.displaySmall
                                .copy(fontFeatureSettings = "tnum", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(24.dp))
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.filter { c -> c.isDigit() || c == '-' } },
                            placeholder = { Text("答案", textAlign = TextAlign.Center) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.headlineSmall
                                .copy(fontFeatureSettings = "tnum", textAlign = TextAlign.Center),
                            modifier = Modifier.fillMaxWidth()
                        )
                        // 答对自动进入下一题；全部答对后可确认
                        androidx.compose.runtime.LaunchedEffect(input) {
                            if (input.isNotBlank() && input.trim().toIntOrNull() == question.second) {
                                solved++
                                question = VerifyGate.genQuiz()
                                input = ""
                            }
                        }
                        if (input.isNotBlank() && input.trim().toIntOrNull() != question.second) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "答案不对，再算算",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(onClick = {
                                question = VerifyGate.genQuiz(); input = ""
                            }) { Text("换一题") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onDismiss) { Text("取消") }
                            Spacer(Modifier.width(8.dp))
                            Button(enabled = ok, onClick = { onConfirmed() }) { Text("确认解锁") }
                        }
                    }
                    VerifyGate.METHOD_PASSWORD -> {
                        var input by remember { mutableStateOf("") }
                        val ok = req.passwordOk(input)
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("解锁密码") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (input.isNotBlank() && !ok) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "密码不正确",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.fillMaxWidth()) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onDismiss) { Text("取消") }
                            Spacer(Modifier.width(8.dp))
                            Button(enabled = ok, onClick = { onConfirmed() }) { Text("确认解锁") }
                        }
                    }
                    VerifyGate.METHOD_DELAY -> {
                        val total = req.delaySec.coerceAtLeast(1)
                        var remaining by remember { mutableIntStateOf(total) }
                        LaunchedEffect(Unit) {
                            while (remaining > 0) {
                                delay(1000)
                                remaining--
                            }
                        }
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "$remaining",
                                style = MaterialTheme.typography.displayLarge
                                    .copy(fontFeatureSettings = "tnum", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                color = if (remaining > 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "秒后可解锁",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.fillMaxWidth()) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onDismiss) { Text("取消") }
                            Spacer(Modifier.width(8.dp))
                            Button(enabled = remaining <= 0, onClick = { onConfirmed() }) {
                                Text(if (remaining > 0) "请等待" else "确认解锁")
                            }
                        }
                    }
                    else -> {
                        Text("确认继续？", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.fillMaxWidth()) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onDismiss) { Text("取消") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { onConfirmed() }) { Text("确认") }
                        }
                    }
                }
            }
        }
    }
}
