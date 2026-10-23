package com.activitylock.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.VerifyGate
import com.activitylock.app.ui.screens.relockDelayLabel

/**
 * 解锁条件编辑器：**总开关** + 跟随上级 / 等待倒计时 / 答对N道口算题 / 随机密码 / 输入密码。
 * - 开关关闭 = 不设解锁条件（MODE_NONE，解锁时不再要求任何验证）。
 *   把门槛调弱仍受调用方的「削弱需验证」闸门约束，这里不重复做安全判断。
 * - 参数只显示在所选方式下方，互不干扰
 * - 标签动态化（题数/位数随输入实时变化）
 *
 * ★ 「跟随上级」的上级是谁取决于调用方：界面级编辑器跟随**应用级**，
 *   应用级编辑器跟随**全局**。
 */
@Composable
fun UnlockConditionEditor(
    mode: String?,                       // null=跟随上级
    delaySec: Int,
    password: String,
    quizCount: Int,
    randomDigits: Int,
    onModeChange: (String?) -> Unit,
    onDelayChange: (Int) -> Unit,    onPasswordChange: (String) -> Unit,
    onQuizCountChange: (Int) -> Unit,
    onRandomDigitsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val condOn = mode != VerifyGate.MODE_NONE

    // ★ v1.24.9：等待秒数的草稿态 + flush 逻辑整块删除。
    //   原因：用户明确要求「不要范围设定在 5～3600 秒，秒/分/时可以自由选择输入」。
    //   现在等待时长直接交给全项目统一的 [DurationField]（秒 / 分钟 / 小时 三段式 +
    //   等效时长回显），组件内部自管草稿，**不 clamp、不报错**，编辑即回调秒数。
    //   于是三个调用点（界面级 / 应用级 / 锁定时弹窗）一次全部对齐。

    // ★ v1.24.7：答题数改草稿态（不再 coerceIn 静默改写）。
    // ★ v1.24.9 收口三件事：
    //   ① 草稿**合法即即时回调** —— 旧实现只在切换单选/开关时 flush，
    //      用户改完数字直接点「保存」，新值没进父级 state，保存下来还是旧值；
    //   ② flush 的职责只剩「丢弃非法草稿、恢复显示当前生效值」，不再 coerceIn 改写；
    //   ③ 位数输入（原是全项目最后一处 `coerceIn(4,12)` 静默改写：输「1」会
    //      立刻跳成「4」）纳入同一口径。
    var quizDraft by remember(quizCount) { mutableStateOf(quizCount.toString()) }
    var digitsDraft by remember(randomDigits) { mutableStateOf(randomDigits.toString()) }
    fun flushQuizDraft() {
        quizDraft = quizCount.toString()
    }
    fun flushDigitsDraft() {
        digitsDraft = randomDigits.toString()
    }
    /** 切换单选/开关、改变条件类型时，把所有输入草稿回落到当前生效值。 */
    fun flushDrafts() {
        flushQuizDraft()
        flushDigitsDraft()
    }

    Column(modifier.fillMaxWidth()) {
        // ★ 总开关：关 = 不开启解锁条件。之前编辑器里没有这一档，
        //   想让某个 App「直接解锁、不验证」只能去改上级，绕得很。
        Row(
            Modifier.fillMaxWidth().clickable {
                flushDrafts()
                onModeChange(if (condOn) VerifyGate.MODE_NONE else null)
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("解锁条件", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = condOn, onCheckedChange = {
                    flushDrafts()
                    onModeChange(if (it) null else VerifyGate.MODE_NONE)
                })
        }
        Spacer(Modifier.height(4.dp))
        if (condOn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(
                    selected = mode == null, onClick = { flushDrafts(); onModeChange(null) }
                )
                Text("跟随上级", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(
                    selected = mode == VerifyGate.METHOD_DELAY,
                    onClick = { flushDrafts(); onModeChange(VerifyGate.METHOD_DELAY) }
                )
                Text("等待倒计时", style = MaterialTheme.typography.bodyMedium)
            }
            if (mode == VerifyGate.METHOD_DELAY) {
                // ★ v1.24.9：统一走 [DurationField]（秒 / 分钟 / 小时、无范围限制）。
                //   旧实现在这里写死「秒 + 5–3600」：想设「等 2 小时」只能填 7200，
                //   填 9999 还会被静默改成 3600。现改为与全 App 同一套时长输入。
                Box(Modifier.padding(start = 40.dp, top = 4.dp, end = 8.dp)) {
                    DurationField(
                        seconds = delaySec,
                        onChange = onDelayChange,
                        equivalent = { sec -> "解锁前需等待 " + relockDelayLabel(sec) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(
                    selected = mode == VerifyGate.METHOD_QUIZ,
                    onClick = { flushDrafts(); onModeChange(VerifyGate.METHOD_QUIZ) }
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
                        value = quizDraft,
                        onValueChange = { raw ->
                            val digits = raw.filter { c -> c.isDigit() }.take(3)
                            quizDraft = digits
                            // ★ 合法即即时回调：改完直接点「保存」也不丢值。
                            digits.toIntOrNull()?.takeIf { it in 1..999 }?.let(onQuizCountChange)
                        },
                        // ★ v1.24.7：与等待秒数同一套口径（草稿态 + 越界标红 + 明示范围），
                        //   不再 `coerceIn(1,10)` 静默改写（输 88 会被悄悄变成 10）。
                        isError = quizDraft.isNotEmpty() &&
                            quizDraft.toIntOrNull()?.let { it !in 1..999 } == true,
                        supportingText = {
                            Text(
                                if (quizDraft.isNotEmpty() && quizDraft.toIntOrNull()?.let { it !in 1..999 } == true)
                                    "只能填 1 – 999"
                                else "范围 1 – 999",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (quizDraft.isNotEmpty() && quizDraft.toIntOrNull()?.let { it !in 1..999 } == true)
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        suffix = { Text("题") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum", textAlign = TextAlign.Center),
                        modifier = Modifier.width(80.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(
                    selected = mode == VerifyGate.METHOD_RANDOM,
                    onClick = { flushDrafts(); onModeChange(VerifyGate.METHOD_RANDOM) }
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
                        value = digitsDraft,
                        onValueChange = { raw ->
                            val digits = raw.filter { c -> c.isDigit() }.take(2)
                            digitsDraft = digits
                            // ★ 合法即即时回调；越界只标红，绝不静默改写（输 1 不会被跳成 4）。
                            digits.toIntOrNull()?.takeIf { it in 4..12 }?.let(onRandomDigitsChange)
                        },
                        isError = digitsDraft.isNotEmpty() &&
                            digitsDraft.toIntOrNull()?.let { it !in 4..12 } == true,
                        supportingText = {
                            Text(
                                if (digitsDraft.isNotEmpty() && digitsDraft.toIntOrNull()?.let { it !in 4..12 } == true)
                                    "只能填 4 – 12"
                                else "范围 4 – 12",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (digitsDraft.isNotEmpty() && digitsDraft.toIntOrNull()?.let { it !in 4..12 } == true)
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        suffix = { Text("位") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum", textAlign = TextAlign.Center),
                        modifier = Modifier.width(80.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(
                    selected = mode == VerifyGate.METHOD_PASSWORD,
                    onClick = { flushDrafts(); onModeChange(VerifyGate.METHOD_PASSWORD) }
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
        }
        Spacer(Modifier.height(4.dp))
    }
}
