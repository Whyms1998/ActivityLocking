package com.activitylock.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.activitylock.app.core.AutoRelockStore
import com.activitylock.app.ui.screens.relockDelayLabel

/**
 * 值选择弹层（文档 §8.9 / §14.10）—— **全 App 共用这一个**。
 *
 * 规则：
 *  1. 选项**固定 2 行**（跟随上级 / 自定义时间），不随预设档位膨胀；
 *  2. **不提供「不锁」入口** —— 「不锁」由场景行右侧的开关唯一表达；
 *  3. 「跟随上级」右侧用浅灰小字显示上级当前值（如 `上级 10 秒`）；
 *  4. 选中「自定义时间」后展开输入区（`−` / 数字框 / `+` / `[秒|分钟]`）；
 *  5. 秒 `0–59`（0 = 立即）、分钟 `1–60`，切换单位按新范围重新钳制；
 *  6. 全局层（[allowFollow] = false）**不出现「跟随上级」**。
 *
 * @param sceneName   场景名，用于标题（如「解锁后」）
 * @param allowFollow 是否允许「跟随上级」
 * @param parentName  上级名称（"全局" / "应用"），用于「跟随上级」的附注
 * @param parentSec   上级**生效**秒数，实时反映上级当前值
 * @param initFollow  当前是否为「跟随上级」
 * @param initSec     当前自定义秒数（[initFollow] 为 true 时可忽略）
 */
@Composable
internal fun SceneValueDialog(
    sceneName: String,
    allowFollow: Boolean,
    parentName: String,
    parentSec: Int,
    initFollow: Boolean,
    initSec: Int,
    onDismiss: () -> Unit,
    onConfirm: (follow: Boolean, sec: Int) -> Unit,
) {
    // ★ v1.24.8：范围限制、秒/分钟两档、单位换算、`step()` 全部删除。
    //   用户 2026-10-13 明确要求：「这个地方我不需要限制范围，就是任意数字都可以，
    //   而且单位也不应该固定成秒」。现在时长编辑统一交给 [DurationField]
    //   （秒 / 分钟 / 小时 三档 + 等效时长回显）。
    var follow by remember { mutableStateOf(allowFollow && initFollow) }

    val initSecSafe = initSec.coerceIn(0, AutoRelockStore.MAX_DELAY_SEC)
    /** 初始单位与初始数值：由 [DurationUnit.bestFor] 按"能整除就用更大单位"挑。 */
    var unit by remember { mutableStateOf(DurationUnit.bestFor(initSecSafe)) }
    var text by remember { mutableStateOf((initSecSafe / unit.seconds).toString()) }
    /** -1 = 还没被用户编辑过（此时以 [text] + [unit] 为准）。 */
    var editedSec by remember { mutableStateOf(-1) }
    /** 当前生效单位，供 [DurationField] 的 onChange 反推显示值。 */
    fun unitOf(): DurationUnit = unit

    /** 当前时长（秒）。 */
    val currentSec: Int =
        if (editedSec >= 0) editedSec else durationSecondsOf(text, unit)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // ★ 底部弹层形态（文档 §8.9「值选择弹层」）：面板贴屏幕底、顶部圆角。
        //   ★★ 这里**只能用 fillMaxWidth**：用 fillMaxSize 会把 Dialog 窗口撑成全屏高，
        //     键盘弹出时 adjustResize 从底部收缩窗口而 Compose 不重新测量 →
        //     整张面板（含「确定」）被裁到可视区之外，表现为"点数字框后弹层消失"。
        //     wrap 高度的窗口可以被系统整扇上移到键盘之上（详见 DialogBottomSheet 注释）。
        Box(Modifier.fillMaxWidth()) {
            Surface(
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(vertical = 14.dp)) {
                    // ★ 贴底 + 随键盘上移。必须在内容 lambda 内部调用（写在外面会静默失效）。
                    DialogBottomSheet()

                    Text(
                        "${sceneName}重锁",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        textAlign = TextAlign.Center
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )

                    if (allowFollow) {
                        ChoiceRow(
                            selected = follow,
                            title = "跟随上级",
                            note = "上级 " + relockDelayLabel(parentSec),
                            onClick = { follow = true }
                        )
                    }
                    ChoiceRow(
                        selected = !follow,
                        title = "自定义时间",
                        note = if (!follow) relockDelayLabel(currentSec) else "",
                        onClick = { follow = false }
                    )

                    if (!follow) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                        Spacer(Modifier.height(12.dp))
                        // ★ v1.24.8：这一整块换成统一的 [DurationField]。
                        //   改动点：① 多了「小时」单位（原来只有秒/分钟）；
                        //   ② 不再有任何范围限制（用户 2026-10-13 明确要求"任意数字都可以"）；
                        //   ③ 去掉 StepButton 的 ±（有了单位分段后它只会添乱：
                        //   "秒"档 +1 是 1 秒，"小时"档 +1 是 1 小时，步长语义不一致）。
                        Row(
                            Modifier.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "自定义",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(12.dp))
                            DurationField(
                                seconds = initSecSafe,
                                onChange = { sec ->
                                    val u = unitOf()
                                    val n = (sec / u.seconds).toString()
                                    text = n.ifEmpty { "0" }
                                    editedSec = sec
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // ★ v1.24.8：范围提示整条删除 —— 用户明确要求「不需要限制范围，
                        //   任意数字都可以」。等效时长由 [DurationField] 自己回显。
                    }

                    Spacer(Modifier.height(14.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable {
                                onConfirm(
                                    if (allowFollow && follow) true else false,
                                    if (allowFollow && follow) -1 else currentSec,
                                )
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "确定",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单选行：左侧圆圈 + 标题 + 右侧附注（选中项附注变强调色）。 */
@Composable
private fun ChoiceRow(
    selected: Boolean,
    title: String,
    note: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clickable { onClick() }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(18.dp)
                .border(
                    1.5.dp,
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        if (note.isNotEmpty()) {
            Text(
                note,
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}


/** 单位分段控件 `[ 秒 | 分钟 ]`（文档 §8.9）。 */

