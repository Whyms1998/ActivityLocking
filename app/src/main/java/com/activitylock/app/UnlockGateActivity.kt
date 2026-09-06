package com.activitylock.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.LockEngine
import com.activitylock.app.ui.components.UnlockVerifyDialog
import com.activitylock.app.ui.theme.ActivityLockTheme
import kotlinx.coroutines.runBlocking

/**
 * 磁贴「全部解锁」的自律验证页：透明背景 + 居中验证卡片。
 * 验证通过后执行全部解锁并关闭。
 */
class UnlockGateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStore.ensureInit(applicationContext)
        setContent {
            ActivityLockTheme {
                Surface(
                    Modifier.fillMaxSize(),
                    color = Color(0x66000000)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        GateCard(
                            onConfirmed = {
                                Thread {
                                    runBlocking {
                                        for (r in AppStore.rules) {
                                            if (r.locked) {
                                                LockEngine.apply(r.packageName, r.activityName, false)
                                            }
                                        }
                                    }
                                    com.activitylock.app.core.AmsController.writeRules()
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
private fun GateCard(onConfirmed: () -> Unit, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val usePassword = com.activitylock.app.core.VerifyGate.method(ctx) ==
        com.activitylock.app.core.VerifyGate.METHOD_PASSWORD
    val correct = com.activitylock.app.core.VerifyGate.password(ctx)
    var passed by remember { mutableStateOf(false) }

    Card(Modifier.width(320.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("全部解锁验证", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (usePassword) {
                var input by remember { mutableStateOf("") }
                androidx.compose.material3.OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("解锁密码") },
                    singleLine = true
                )
                if (input.isNotBlank() && input != correct) {
                    Spacer(Modifier.height(4.dp))
                    Text("密码不正确", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
                passed = input.isNotBlank() && input == correct
            } else {
                val total = com.activitylock.app.core.VerifyGate.delaySeconds(ctx).coerceAtLeast(1)
                var remaining by remember { mutableStateOf(total) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    while (remaining > 0) {
                        kotlinx.coroutines.delay(1000)
                        remaining--
                    }
                }
                Text(
                    if (remaining > 0) "自律提醒：请等待 $remaining 秒后再全部解锁。"
                    else "等待完成，可以解锁了。",
                    style = MaterialTheme.typography.bodyMedium
                )
                passed = remaining <= 0
            }
            Spacer(Modifier.height(12.dp))
            androidx.compose.foundation.layout.Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                TextButton(enabled = passed, onClick = onConfirmed) {
                    Text(if (passed) "确认全部解锁" else "请等待…")
                }
            }
        }
    }
}
