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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.VerifyGate
import kotlinx.coroutines.delay

/**
 * 解锁验证对话框：按设置要求倒计时等待或输入密码。
 * 验证通过后回调 onConfirmed。
 */
@Composable
fun UnlockVerifyDialog(onConfirmed: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val usePassword = VerifyGate.method(ctx) == VerifyGate.METHOD_PASSWORD
    val correctPassword = VerifyGate.password(ctx)

    if (usePassword) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("解锁验证") },
            text = {
                Column {
                    Text("自律提醒：输入解锁密码以继续。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("解锁密码") },
                        singleLine = true
                    )
                    if (input.isNotBlank() && input != correctPassword) {
                        Spacer(Modifier.height(4.dp))
                        Text("密码不正确", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = input.isNotBlank() && input == correctPassword,
                    onClick = { onConfirmed() }
                ) { Text("确认解锁") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
    } else {
        val total = VerifyGate.delaySeconds(ctx).coerceAtLeast(1)
        var remaining by remember { mutableIntStateOf(total) }
        LaunchedEffect(Unit) {
            while (remaining > 0) {
                delay(1000)
                remaining--
            }
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("解锁验证") },
            text = {
                Text(
                    if (remaining > 0) "自律提醒：请等待 $remaining 秒后再解锁。"
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
}
