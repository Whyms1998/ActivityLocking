package com.activitylock.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.VerifyGate
import kotlinx.coroutines.delay

/**
 * 解锁验证对话框：按给定条件倒计时等待或输入密码。
 * requirement 为空时使用全局配置；条件为 none 时调用方不应弹出本对话框。
 * 验证通过后回调 onConfirmed。
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

    when (req.mode) {
        VerifyGate.METHOD_QUIZ -> {
            var question by remember { mutableStateOf(VerifyGate.genQuiz()) }
            var input by remember { mutableStateOf("") }
            val ok = input.isNotBlank() && input.trim().toIntOrNull() == question.second
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Column {
                        Text("$hint：答对下面这道口算题以继续。", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            question.first,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.filter { c -> c.isDigit() || c == '-' } },
                            placeholder = { Text("答案") },
                            singleLine = true,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        if (input.isNotBlank() && !ok) {
                            Spacer(Modifier.height(4.dp))
                            Text("答案不对，再算算", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = ok, onClick = { onConfirmed() }) { Text("确认解锁") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        question = VerifyGate.genQuiz(); input = ""
                    }) { Text("换一题") }
                }
            )
        }
        VerifyGate.METHOD_PASSWORD -> {
            var input by remember { mutableStateOf("") }
            val ok = req.passwordOk(input)
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Column {
                        Text("$hint：输入解锁密码以继续。", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
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
                    }
                },
                confirmButton = {
                    TextButton(enabled = ok, onClick = { onConfirmed() }) { Text("确认解锁") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
            )
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
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Text(
                        if (remaining > 0) "$hint：请等待 $remaining 秒后再解锁。"
                        else "等待完成，可以解锁了。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    TextButton(enabled = remaining <= 0, onClick = { onConfirmed() }) {
                        Text(if (remaining > 0) "请等待 ${remaining}s" else "确认解锁")
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
            )
        }
        else -> {
            // 无验证要求：直接确认（防御性分支，正常不应走到）
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = { Text("确认继续？", style = MaterialTheme.typography.bodyMedium) },
                confirmButton = { TextButton(onClick = { onConfirmed() }) { Text("确认") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
            )
        }
    }
}
