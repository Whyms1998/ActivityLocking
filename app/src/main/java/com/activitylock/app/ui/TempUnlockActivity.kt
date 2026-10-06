package com.activitylock.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AmsController
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.LockStrength
import com.activitylock.app.core.UnlockExemptions
import com.activitylock.app.core.VerifyGate
import com.activitylock.app.ui.theme.ActivityLockTheme

/**
 * 临时解锁验证页（磁贴拉起）：通过所有锁定界面的最强解锁条件后，
 * 全部临时解锁 LockStrength 时长，到期自动落锁。
 */
class TempUnlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStore.ensureInit(applicationContext)
        val duration = LockStrength.tempUnlockMin(this)
        val lockedRules = AppStore.rules.filter { it.locked }
        val lockedCount = lockedRules.size
        val memberReq = VerifyGate.bulkRequirement(this, lockedRules)
        val requirement = if (memberReq.isNone) {
            VerifyGate.Requirement(VerifyGate.METHOD_QUIZ, 0, emptyList())
        } else memberReq

        setContent {
            ActivityLockTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp)
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(20.dp))
                        Text("临时解锁", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "解锁全部 $lockedCount 个界面 $duration 分钟，到期自动落锁。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(40.dp))

                        when (requirement.mode) {
                            VerifyGate.METHOD_RANDOM -> {
                                var target by remember { mutableStateOf(VerifyGate.genRandomCode(requirement.randomDigits)) }
                                var input by remember { mutableStateOf("") }
                                val ok = input == target
                                Text(
                                    "照抄随机密码后临时解锁：",
                                    style = MaterialTheme.typography.bodyMedium
                                )
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
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (input.isNotBlank() && !ok) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("不一致，请照抄", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                                }
                                Spacer(Modifier.weight(1f))
                                Actions(ok, onDismiss = { finish() }) {
                                    confirmUnlock(duration)
                                }
                            }
                            VerifyGate.METHOD_QUIZ -> {
                                var solved by remember { mutableIntStateOf(0) }
                                var question by remember { mutableStateOf(VerifyGate.genQuiz()) }
                                var input by remember { mutableStateOf("") }
                                val total = requirement.quizCount.coerceAtLeast(1)
                                val ok = solved >= total
                                val wrong = input.isNotBlank() &&
                                    input.trim().toIntOrNull() != question.second
                                if (total > 1) {
                                    Text(
                                        "第 ${solved + 1} / $total 题",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                                Text(
                                    question.first,
                                    style = MaterialTheme.typography.displaySmall
                                        .copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold)
                                )
                                Spacer(Modifier.height(24.dp))
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { raw ->
                                        input = raw.filter { c -> c.isDigit() || c == '-' }
                                        if (input.isNotBlank() && input.trim().toIntOrNull() == question.second) {
                                            solved++
                                            if (solved < total) {
                                                input = ""
                                                question = VerifyGate.genQuiz()
                                            }
                                        }
                                    },
                                    placeholder = { Text("答案") },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (wrong) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("答案不对，再算算", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                                }
                                Spacer(Modifier.weight(1f))
                                Actions(ok, onDismiss = { finish() }) {
                                    confirmUnlock(duration)
                                }
                            }
                            VerifyGate.METHOD_PASSWORD -> {
                                var input by remember { mutableStateOf("") }
                                val ok = requirement.passwordOk(input)
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { input = it },
                                    placeholder = { Text("解锁密码") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (input.isNotBlank() && !ok) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("密码不正确", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                                }
                                Spacer(Modifier.weight(1f))
                                Actions(ok, onDismiss = { finish() }) {
                                    confirmUnlock(duration)
                                }
                            }
                            VerifyGate.METHOD_DELAY -> {
                                var remaining by remember { mutableStateOf(requirement.delaySec) }
                                androidx.compose.runtime.LaunchedEffect(Unit) {
                                    while (remaining > 0) {
                                        kotlinx.coroutines.delay(1000)
                                        remaining--
                                    }
                                }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "$remaining",
                                        style = MaterialTheme.typography.displayLarge
                                            .copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text("秒后可解锁", style = MaterialTheme.typography.bodyMedium)
                                }
                                Spacer(Modifier.weight(1f))
                                Actions(remaining <= 0, onDismiss = { finish() }) {
                                    confirmUnlock(duration)
                                }
                            }
                            else -> {
                                Spacer(Modifier.weight(1f))
                                Actions(true, onDismiss = { finish() }) {
                                    confirmUnlock(duration)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun confirmUnlock(duration: Int) {
        UnlockExemptions.addGlobal(applicationContext, System.currentTimeMillis() + duration * 60_000L)
        AmsController.writeRules()
        // 打开桌面（用户自行选择要用的 App）
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    @Composable
    private fun Actions(ok: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.weight(1f))
            Button(enabled = ok, onClick = onConfirm) { Text("解锁") }
        }
    }
}
