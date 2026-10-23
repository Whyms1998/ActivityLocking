package com.activitylock.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.activitylock.app.ui.screens.relockDelayLabel

/**
 * ★ v1.24.8：全项目统一的「时长输入」。
 *
 * ## 为什么需要它（用户 2026-10-13 的原话）
 * > 「app 上所有需要输入时间的地方都应该优化，设计一个比较好的方式，
 * >   不管输入想输入按秒、按分钟、按小时都可以，而且显示还是很简洁。」
 *
 * 改造前的状况是**每个地方各写一套**：
 *  - `SceneValueDialog`：只有 秒 / 分钟 两档，且切换单位时做 60 倍换算；
 *  - `VerifySettingsScreen` / `UnlockConditionEditor`：只有「秒」，字段名直接叫 `xxxSec`；
 *  - `AutoFreezeScreen`：秒 / 分钟 两档，另写了一套 `UnitChip`；
 *  - `LockStrength`：固定 5/10/15/30 分钟四个预设，写死 `coerceIn(5, 30)`。
 * 五处 UI 各不相同，用户每换一个设置就要重新学一次。
 *
 * ## 设计取舍（对齐业界成熟做法）
 * 参照 **Google Clock 计时器**（数字框 + 单位分段）与 **iOS「屏幕使用时间」**
 *（预设 + 自定义），本组件取二者之长：
 *
 * ```
 * ┌────────────────────────────────────┐
 * │  [   30   ]   [秒] [分钟] [小时]   │   ← 单行：数值 + 单位，44dp 触控高度
 * │  实际时长 30 分钟                    │   ← 等效时长回显，用户永远知道自己填了多少
 * └────────────────────────────────────┘
 * ```
 *
 * 三条关键决定：
 * 1. **切换单位时数值保持不变**（Google Clock 的做法），不做 60 倍换算。
 *    换算的"聪明"对用户是负担：30 分钟切到小时会变成 0 或 1，凭空改变语义。
 *    改用「下方永远回显等效时长」来消除歧义 —— 填 2 小时，下面就写"实际时长 2 小时"。
 * 2. **不做范围限制**。用户明确要求"任意数字都可以"。这里只做
 *    「过滤非数字 + 限位数 + 防 Int 溢出」三件事，越界一律不报错、不改写。
 * 3. **单位用分段控件而非下拉**：三个选项一次全露出来，少一次点击，
 *    也避免下拉里出现"3 分钟 / 3 小时"这种歧义项。
 *
 * ## v1.24.9 收口
 * 上一版只覆盖了「三场景延迟」与「临时解锁时长」，用户随后在
 * 「解锁验证 → 等待」处又看到一个还带 `范围 5 – 3600 秒` 的旧输入框
 * （用户原话：「不需要范围设定在 5～3600 秒，应该也是秒、分、时可以自由选择输入」）。
 * 本版把所有**以时长为值**的输入统一到本组件：
 * `UnlockConditionEditor`（等待·界面级/应用级/锁定时）、`VerifySettingsScreen`（等待·全局）、
 * `AutoFreezeScreen`（退出延迟）、`QuotaScreen`（每日限额 / 连续使用 / 宽限期）、
 * `FavoritesScreen` 与 `RelockActivityDetailScreen`（单界面每日限额）。
 * 各场景用 [units] 声明自己能无损承载的粒度，用 [equivalent] 给出各自的人话回显。
 */
enum class DurationUnit(val label: String, val seconds: Int) {
    SEC("秒", 1),
    MIN("分钟", 60),
    HOUR("小时", 3600);

    companion object {
        /** 三档全开（默认）。 */
        val ALL: List<DurationUnit> = listOf(SEC, MIN, HOUR)

        /**
         * 为一个时长挑最合适的展示单位：能整除时优先用更大的单位。
         *
         * ★ v1.24.9：支持**限定单位集**。有些场景的底层粒度就是分钟（每日限额按分钟计、
         *   离开宽限期按秒计），放开「秒」只会让用户填出一个存不进去的值
         *   —— 那种"填了 90 秒、存下来变成 1 分钟"的静默改写正是用户最反感的。
         *   所以调用方用 [units] 声明自己能无损承载的单位，组件保证永不取整。
         */
        fun bestFor(sec: Int, units: List<DurationUnit> = ALL): DurationUnit =
            units.sortedByDescending { it.seconds }
                .firstOrNull { sec >= it.seconds && sec % it.seconds == 0 }
                ?: units.minByOrNull { it.seconds }
                ?: SEC
    }
}

/** 换算防溢出的硬上限（约 3 年）。**不是业务限制**，只是防止 Int 溢出。 */
const val MAX_DURATION_SEC = 100_000_000

/** 「文本 + 单位」→ 秒。空串 / 非法一律当 0。 */
fun durationSecondsOf(text: String, unit: DurationUnit): Int {
    val n = text.trim().toIntOrNull() ?: 0
    if (n <= 0) return 0
    val sec = n.toLong() * unit.seconds
    return if (sec > MAX_DURATION_SEC) MAX_DURATION_SEC else sec.toInt()
}

/**
 * 统一的时长输入行。
 *
 * @param seconds 当前时长（秒）。只在**首次进入**时决定初始数值与单位；
 *                之后的编辑完全由内部草稿驱动，不会被外部回写打断输入。
 * @param onChange 每次有效编辑都回调换算后的秒数。
 * @param maxDigits 数值最多几位。默认 4（最大 9999 小时 ≈ 1.1 年，已远超实际需要）。
 * @param units 本场景允许的单位集。默认三档全开；底层只存分钟的场合传 `[分钟, 小时]`。
 * @param equivalent 下方等效时长的文案生成器。各场景语义不同
 *                   （「等待 5 分钟」/「离开 30 秒后冻结」/「每天可用 1 小时」），
 *                   统一由调用方给一句人话，避免出现「实际时长 立即」这种怪句。
 */
@Composable
fun DurationField(
    seconds: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    maxDigits: Int = 4,
    showEquivalent: Boolean = true,
    units: List<DurationUnit> = DurationUnit.ALL,
    equivalent: (Int) -> String = { sec -> "实际时长 " + relockDelayLabel(sec) },
) {
    val initUnit = remember(seconds, units) { DurationUnit.bestFor(seconds, units) }
    var unit by remember { mutableStateOf(initUnit) }
    var text by remember { mutableStateOf((seconds / initUnit.seconds).toString()) }

    val shownSec = durationSecondsOf(text, unit)

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // ★ v1.24.9 收口：数值框与右侧「秒/分钟/小时」分段**同款式** ——
            //   同高 44dp、同圆角 9dp、同底色 surfaceContainerLowest、无描边。
            //   原来用 OutlinedTextField 是白底 + 描边 + 56dp 高，与分段并排时
            //   像两套体系的控件拼在一起（用户真机截图点名要求统一）。
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.width(104.dp).height(44.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BasicTextField(
                        value = text,
                        onValueChange = { raw ->
                            // ★ 只过滤非数字 + 限位数。**绝不 clamp、不报错** ——
                            //   用户明确要求"任意数字都可以"（v1.24.8）。
                            val digits = raw.filter { it.isDigit() }.take(maxDigits)
                            text = digits.ifEmpty { "" }
                            onChange(durationSecondsOf(digits, unit))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            fontFeatureSettings = "tnum",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            UnitTriSegment(units, unit) { picked ->
                // ★ 切换单位保持数值不变，语义不变；下方回显负责消除歧义。
                unit = picked
                onChange(durationSecondsOf(text, picked))
            }
        }
        if (showEquivalent) {
            Spacer(Modifier.height(6.dp))
            Text(
                equivalent(shownSec),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 秒 / 分钟 / 小时 分段单位选择（可只开放其中若干档，见 [DurationUnit.bestFor]）。 */
@Composable
internal fun UnitTriSegment(
    units: List<DurationUnit>,
    selected: DurationUnit,
    onPick: (DurationUnit) -> Unit,
) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
            Row {
                for (u in units) {
                    SegmentCell(u.label, u == selected) { onPick(u) }
                }
            }
    }
}

@Composable
internal fun SegmentCell(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(9.dp),
        modifier = Modifier
            .defaultMinSize(minHeight = 44.dp)
            .clickable { onClick() }
    ) {
        Box(
            Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Visible
            )
        }
    }
}

/**
 * 「自定义时间」弹层：标题 + [DurationField] + 确定/取消。
 * 用于原本只有固定预设、现在需要自由输入的场景（临时解锁时长等）。
 */
@Composable
fun DurationPickerDialog(
    title: String,
    initialSec: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var draft by remember { mutableStateOf(initialSec) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // ★ v1.24.9 收口：与 SceneValueDialog 同形态（贴底弹层 + 随键盘上移）。
        //   此前是居中 Dialog 且**没有 IME 避让** —— 点数字框弹键盘会重现
        //   「整张弹层被裁到可视区外」的老坑（详见 DialogBottomSheet 注释）；
        //   而且「值选择弹层」全 App 有两种形态本身就不统一。
        Box(Modifier.fillMaxWidth()) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(vertical = 14.dp)) {
                    // 贴底 + 随键盘上移。必须在内容 lambda 内部调用（写在外面会静默失效）。
                    DialogBottomSheet()

                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.padding(horizontal = 14.dp)) {
                        DurationField(seconds = initialSec, onChange = { draft = it })
                    }
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable { onConfirm(draft) }
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
