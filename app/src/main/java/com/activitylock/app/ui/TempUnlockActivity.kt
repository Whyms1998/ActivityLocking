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
import com.activitylock.app.core.ScheduleEngine
import com.activitylock.app.core.UnlockExemptions
import com.activitylock.app.core.VerifyGate
import com.activitylock.app.ui.screens.relockDelayLabel
import com.activitylock.app.ui.theme.ActivityLockTheme

/**
 * 临时解锁验证页（磁贴拉起）：通过所有锁定界面的最强解锁条件后，
 * 全部临时解锁 LockStrength 时长，到期自动落锁。
 */
class TempUnlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStore.ensureInit(applicationContext)
        // ★ v1.24.9 修复（一）：全程按**秒**。
        //   原来读 tempUnlockMin()（= 秒 / 60 的**整数除法**），用户把临时解锁时长
        //   填成 90 秒，会被砍成 1 分钟放行 —— 正是用户最反感的「静默改写」。
        val durationSec = LockStrength.tempUnlockSec(this)
        // ★ v1.24.9 修复（二）：每日次数闸门真正生效。
        //   此前 tempUnlockAllowed / recordTempUnlock 是死代码，上限形同虚设。
        val allowed = LockStrength.tempUnlockAllowed(this)
        val dailyMax = LockStrength.tempUnlockDailyMax(this)
        val usedToday = LockStrength.tempUnlockUsedToday(this)
        // ★ v1.24.9 修复（三）：计划窗口闸门。
        //   「计划窗口内禁用临时解锁」此前只在 AmsController 下发规则时抑制豁免 ——
        //   验证页照常放行：用户走完验证、看到「解锁全部 N 个界面」，
        //   但 Watcher 一个都不放（假动作），回到桌面点 App 仍被拦。
        //   现在与次数闸门同级，进门即拦。
        val planBlocked = LockStrength.noExemptInSchedule(this) &&
            ScheduleEngine.anyActive(AppStore.schedules)
        val lockedRules = AppStore.rules.filter { it.locked }
        val lockedCount = lockedRules.size
        val memberReq = VerifyGate.bulkRequirement(this, lockedRules)
        val requirement = when {
            VerifyGate.outsideUnlockWindow(applicationContext) ->
                VerifyGate.Requirement("window_blocked", 0, emptyList())
            memberReq.isNone ->
                VerifyGate.Requirement(VerifyGate.METHOD_QUIZ, 0, emptyList())
            else -> memberReq
        }

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
                            when {
                                planBlocked ->
                                    "「计划窗口内禁用临时解锁」已开启，当前处于计划生效时段，暂时无法临时解锁。"
                                !allowed && dailyMax == 0 ->
                                    "已在「锁定强度」中禁用临时解锁。"
                                !allowed ->
                                    "今日临时解锁次数已用完（$usedToday / $dailyMax），明天恢复。"
                                else ->
                                    "解锁全部 $lockedCount 个界面 " + relockDelayLabel(durationSec) +
                                        "，到期自动落锁。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(40.dp))

                        // ★ v1.24.9：次数闸门 + 计划窗口闸门 —— 不满足时直接拦下，不进验证流程
                        if (!allowed || planBlocked) {
                            Spacer(Modifier.weight(1f))
                            Row(Modifier.fillMaxWidth()) {
                                TextButton(onClick = { finish() }) { Text("返回") }
                            }
                        } else when (requirement.mode) {
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
                                    confirmUnlock(durationSec)
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
                                        "第 ${minOf(solved + 1, total)} / $total 题",
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
                                        if (solved < total && input.isNotBlank() && input.trim().toIntOrNull() == question.second) {
                                            solved++
                                            input = ""
                                            if (solved < total) question = VerifyGate.genQuiz()
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
                                    confirmUnlock(durationSec)
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
                                    confirmUnlock(durationSec)
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
                                // ★ v1.24.9：与 [UnlockVerifyDialog] 同一口径 ——
                                //   等待时长可任意填，超过 1 分钟换成「12 分 30 秒」式读数。
                                val shortForm = remaining < 60
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (shortForm) "$remaining"
                                        else VerifyGate.delayCountdownLabel(remaining),
                                        style = (if (shortForm) MaterialTheme.typography.displayLarge
                                        else MaterialTheme.typography.headlineMedium)
                                            .copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        if (shortForm) "秒后可解锁" else "后可解锁",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                Actions(remaining <= 0, onDismiss = { finish() }) {
                                    confirmUnlock(durationSec)
                                }
                            }
                            "window_blocked" -> {
                                Text(
                                    "已设置解锁时间范围 " + VerifyGate.unlockWindowDesc(applicationContext) +
                                        "，当前时段无法临时解锁。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                Actions(false, onDismiss = { finish() }) {
                                    confirmUnlock(durationSec)
                                }
                            }
                            else -> {
                                Spacer(Modifier.weight(1f))
                                Actions(true, onDismiss = { finish() }) {
                                    confirmUnlock(durationSec)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun confirmUnlock(durationSec: Int) {
        UnlockExemptions.addGlobal(applicationContext, System.currentTimeMillis() + durationSec * 1000L)
        // ★ v1.24.9：记账 —— 每日次数上限自此真正生效（此前为死代码）
        LockStrength.recordTempUnlock(applicationContext)
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
