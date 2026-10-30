package com.activitylock.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.activitylock.app.core.AmsController
import com.activitylock.app.core.AppStore
import com.activitylock.app.core.LockRule
import com.activitylock.app.core.VerifyGate
import com.activitylock.app.ui.screens.relockDelayLabel

/**
 * 单条规则的「解锁条件」独立编辑对话框。
 *
 * ★ 为什么抽成独立组件：
 *   原先解锁条件埋在「收藏夹 → 铅笔图标（语义还是"备注"）→ 弹窗最底部」，
 *   入口语义不清、层级过深，用户根本找不到"这个 App 单独用什么方式解锁"。
 *   现在收藏夹卡片与自动锁闭页都能一步直达本弹窗。
 *
 * 安全：若本次修改**降低门槛**（换用更弱的方式 / 缩短等待 / 减少题数）且该界面
 * 当前处于锁定态，保存前必须先通过**当前生效条件**的验证 —— 与既有行为一致，
 * 不存在"改弱门槛不用验证"的绕过口子。
 */
@Composable
fun UnlockConditionDialog(
    rule: LockRule,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var vMode by remember(rule) { mutableStateOf(rule.verifyMode) }
    // ★ v1.24.9：等待秒数由 String 草稿改回 Int —— 输入框已交给 [DurationField]
    //   （自带草稿态与单位分段），这里只保留"当前值"。
    var vDelaySec by remember(rule) {
        mutableStateOf(rule.verifyDelaySec ?: VerifyGate.delaySeconds(ctx))
    }
    var vPwd by remember(rule) { mutableStateOf(rule.verifyPassword ?: "") }
    var vQuizCount by remember(rule) {
        mutableStateOf((rule.quizCount ?: VerifyGate.quizCount(ctx)).toString())
    }
    var vRandomDigits by remember(rule) { mutableStateOf(VerifyGate.randomDigits(ctx).toString()) }
    var pendingWeakSave by remember(rule) { mutableStateOf(false) }

    // ★ 界面级未设置时跟随的是「应用级」；应用级也没设才轮到全局。
    //   所以这里的"上级"要取应用级条件，而不是想当然的全局。
    val followReq = VerifyGate.appLevelRequirement(ctx, rule.packageName)
    val curReq = VerifyGate.requirementFor(ctx, rule)

    // 修改后的有效模式（复刻 requirementFor 的解析）
    val afterMode: String = when (vMode) {
        VerifyGate.MODE_NONE -> VerifyGate.MODE_NONE
        VerifyGate.METHOD_DELAY,
        VerifyGate.METHOD_PASSWORD,
        VerifyGate.METHOD_QUIZ,
        VerifyGate.METHOD_RANDOM -> vMode!!
        else -> followReq.mode
    }
    val weakening = rule.locked && (
        VerifyGate.strength(afterMode) < VerifyGate.strength(curReq.mode) ||
            (afterMode == VerifyGate.METHOD_DELAY && curReq.mode == VerifyGate.METHOD_DELAY &&
                vDelaySec < curReq.delaySec)
        )

    fun doSave() {
        AppStore.updateVerify(
            rule.packageName, rule.activityName,
            vMode, vDelaySec, vPwd, vQuizCount.trim().toIntOrNull()
        )
        if (vMode == VerifyGate.METHOD_RANDOM) {
            VerifyGate.setRandomDigits(ctx, vRandomDigits.toIntOrNull() ?: 6)
        }
        AmsController.writeRules()
        AppStore.toast("解锁条件已保存")
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("解锁条件") },
        text = {
            // ★ 条件编辑器含密码/口算题数量等输入框。v1.31.1：居中弹窗改 ADJUST_PAN ——
            //   resize 会让窗口随键盘收缩后重新居中，键盘高度一变就上下跳；PAN 窗口不动。
            com.activitylock.app.ui.components.DialogImePan()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // ★ v1.32.2：原来两行（备注 + 完整类名），长类名还会折行。
                //   合并成一行可读名「酷安 › 首页」——类名细节在编辑弹窗的跳转目标里能看到。
                Text(
                    com.activitylock.app.core.superviseTargetLabel(
                        rule.packageName, rule.activityName
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "当前生效：${requirementDesc(curReq)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                UnlockConditionEditor(
                    mode = vMode,
                    delaySec = vDelaySec,
                    password = vPwd,
                    quizCount = vQuizCount.toIntOrNull() ?: VerifyGate.quizCount(ctx),
                    randomDigits = vRandomDigits.toIntOrNull() ?: 6,
                    onModeChange = { vMode = it },
                    onDelayChange = { vDelaySec = it },
                    onPasswordChange = { vPwd = it },
                    onQuizCountChange = { vQuizCount = it.toString() },
                    onRandomDigitsChange = { vRandomDigits = it.toString() },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (weakening) pendingWeakSave = true else doSave()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    if (pendingWeakSave) {
        UnlockVerifyDialog(
            requirement = curReq,
            title = "降低门槛验证",
            hint = "本次修改会降低该界面的解锁门槛",
            onConfirmed = {
                pendingWeakSave = false
                doSave()
            },
            onDismiss = { pendingWeakSave = false }
        )
    }
}

/** 把 [VerifyGate.Requirement] 转成一句人话，用于「当前生效：…」的展示。 */
fun requirementDesc(req: VerifyGate.Requirement): String = when (req.mode) {
    VerifyGate.MODE_NONE -> "无需验证"
    // ★ v1.24.9：等待时长可自由填（秒/分/小时），这里也用「2 小时」式读数，
    //   否则设了 2 小时会显示成「等待 7200 秒」。
    VerifyGate.METHOD_DELAY -> "等待 " + relockDelayLabel(req.delaySec)
    VerifyGate.METHOD_PASSWORD -> "输入密码"
    VerifyGate.METHOD_QUIZ -> "答对 ${req.quizCount} 道口算题"
    VerifyGate.METHOD_RANDOM -> "照抄 ${req.randomDigits} 位随机密码"
    else -> req.mode
}
