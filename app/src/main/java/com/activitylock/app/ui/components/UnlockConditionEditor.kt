package com.activitylock.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.VerifyGate

/**
 * 解锁条件编辑器：跟随全局 / 等待倒计时 / 答对N道口算题 / 随机密码 / 输入密码。
 * - 参数只显示在所选方式下方，互不干扰
 * - 标签动态化（题数/位数随输入实时变化）
 * - 不提供「无需验证」（锁定的意义即在于此；历史数据仍兼容）
 */
@Composable
fun UnlockConditionEditor(
    mode: String?,                       // null=跟随全局
    delaySec: Int,
    password: String,
    quizCount: Int,
    randomDigits: Int,
    globalDesc: String,
    onModeChange: (String?) -> Unit,
    onDelayChange: (Int) -> Unit,
    onPasswordChange: (String) -> Unit,
    onQuizCountChange: (Int) -> Unit,
    onRandomDigitsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {

    Column(modifier.fillMaxWidth()) {
        Text("解锁条件", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(
                selected = mode == null, onClick = { onModeChange(null) }
            )
            Text("跟随全局", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(
                selected = mode == VerifyGate.METHOD_DELAY,
                onClick = { onModeChange(VerifyGate.METHOD_DELAY) }
            )
            Text("等待倒计时", style = MaterialTheme.typography.bodyMedium)
        }
        if (mode == VerifyGate.METHOD_DELAY) {
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
                            onDelayChange(it.coerceIn(5, 3600))
                        }
                    },
                    suffix = { Text("秒") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum", textAlign = TextAlign.Center),
                    modifier = Modifier.width(96.dp)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(
                selected = mode == VerifyGate.METHOD_QUIZ,
                onClick = { onModeChange(VerifyGate.METHOD_QUIZ) }
            )
            Text("答对口算题", style = MaterialTheme.typography.bodyMedium)
        }
        if (mode == VerifyGate.METHOD_QUIZ) {
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
                            onQuizCountChange(it.coerceIn(1, 10))
                        }
                    },
                    suffix = { Text("题") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum", textAlign = TextAlign.Center),
                    modifier = Modifier.width(80.dp)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(
                selected = mode == VerifyGate.METHOD_RANDOM,
                onClick = { onModeChange(VerifyGate.METHOD_RANDOM) }
            )
            Text("输入随机密码", style = MaterialTheme.typography.bodyMedium)
        }
        if (mode == VerifyGate.METHOD_RANDOM) {
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
                            onRandomDigitsChange(it.coerceIn(4, 12))
                        }
                    },
                    suffix = { Text("位") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum", textAlign = TextAlign.Center),
                    modifier = Modifier.width(80.dp)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(
                selected = mode == VerifyGate.METHOD_PASSWORD,
                onClick = { onModeChange(VerifyGate.METHOD_PASSWORD) }
            )
            Text("输入密码", style = MaterialTheme.typography.bodyMedium)
        }
        if (mode == VerifyGate.METHOD_PASSWORD) {
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(if (password.isBlank()) "留空则使用全局密码" else "该界面专用密码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(start = 40.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
