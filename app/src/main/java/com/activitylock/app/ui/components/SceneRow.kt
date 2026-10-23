package com.activitylock.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 值的两种状态（文档 §8.3「值 → 样式映射」）。
 *
 * ★ 关键设计：「不锁」**不是第三种值状态** —— 它由右侧开关关闭表达。
 *   三态由此降为「开关 + 两态值」，一件事只有一个入口（文档原则 2）。
 */
enum class SceneValueStyle {
    /** 跟随上级：显示上级生效值，灰色。上层一改它同步变。 */
    INHERIT,

    /** 自定义：显示自己的值，蓝色加粗。 */
    CUSTOM,

    /** 上级缺失时（如全局层没有上级）的中性显示。 */
    PLAIN,
}

/** 一行场景的完整描述。 */
data class SceneRowSpec(
    /** 场景名：「解锁后」「退出后」「锁屏后」。 */
    val name: String,
    /** 右侧值文字，如「10 秒」「立即」「跟随上级」。 */
    val valueText: String,
    val style: SceneValueStyle,
    /**
     * 开关状态 = **三层裁决后的最终结果**（文档 §12.3）：开 = 现在真的会锁；关 = 现在一条都不锁。
     *
     * ★★ v1.24.12：这一项曾经拆成 `checked`（本层原始值）+ `inEffect`（裁决结果），
     *   规则是「开关画本层、明暗画裁决」。真机证明那正是**假开关的温床**：只要有一层
     *   漏看总闸，就会出现「开关亮着、实际一条都不锁」，而用户看到「开着」便不会再拨动
     *   → 功能永久失效（2026-10-14 两次事故：① 下层开开关不联动全局总闸；
     *   ② `activityLinkRawByState` 不看本层/应用总闸）。
     *   两轨制已删除：**开关 = 实际效果，一件事只有一个入口**。
     *
     * 只能传裁决结果（`AutoRelock.lockedByState` / `AutoRelock.enabledByState`），
     *   **禁止**传 `appLinkEnabledByState` / `activityLinkRawByState` 那类「已配置值」——
     *   「已配置但被上层暂停」由文字提示表达，不用假的亮灯表达。
     */
    val checked: Boolean,
    /** 整行是否可交互（如 Shizuku 未连接时置灰）。 */
    val enabled: Boolean = true,
    val showSwitch: Boolean = true,
    val onValueClick: () -> Unit = {},
    val onSwitchChange: (Boolean) -> Unit = {},
)

/**
 * 三场景行的统一容器：一张卡片、三行、行间 1dp 分隔线（文档 §8.3）。
 *
 * 页面左右边距由调用方负责（文档 §8.1 铁律：恒定 `page_margin` 16dp）。
 */
@Composable
fun SceneRowsCard(
    rows: List<SceneRowSpec>,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        SceneRowsColumn(rows)
    }
}

/**
 * 同 [SceneRowsCard] 的行体，但**不自带 Surface**。
 *
 * 用于「已经身处某张卡片内部」的场合（如自动锁闭页的管辖范围里，应用卡片本身就是
 * 一张 Card）—— 再套一层 [SceneRowsCard] 会画出第二层圆角底，看起来像套娃。
 */
@Composable
fun SceneRowsColumn(
    rows: List<SceneRowSpec>,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        rows.forEachIndexed { i, r ->
            if (i > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            SceneRow(r)
        }
    }
}

/**
 * 场景行：`场景名（左） → 值（靠右） → 开关（最右）`。
 *
 * 交互（文档 §8.3 / §15.1）：
 *  - 点这一行（场景名 / 值 / 空白处）→ 弹出值选择弹层；
 *  - 点开关 → 直接锁定 / 不锁，**不弹层**。
 *
 * ★ 值的显示规则：
 *  - **开关关着 → 不显示值**。理由：左边挂着「5 分钟」、右边开关是关的，
 *    用户读不出到底等多久，那句时间反而是误导 —— 干脆留空，让「开关 = 锁不锁」
 *    成为唯一信号。值仍存在存储里（重新打开开关即恢复显示），点这一行即可改值。
 *  - **无开关的行**（showSwitch=false）→ 照常显示值，用明暗表达生效与否。
 *
 * ★ v1.24.12：明暗与开关现在**同源**（都读 [SceneRowSpec.checked] = 裁决结果）。
 *   旧版是两轨制（开关读本层原始值、明暗读裁决结果），于是「亮着但不锁」的假开关
 *   反复出现 —— 现在从数据类层面删掉了 `inEffect`，两者不可能再分叉。
 */
@Composable
fun SceneRow(spec: SceneRowSpec) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val valueColor = when {
        !spec.checked -> dim
        spec.style == SceneValueStyle.CUSTOM -> MaterialTheme.colorScheme.primary
        spec.style == SceneValueStyle.INHERIT -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    // 有开关 → 看开关；没开关 → 始终显示（用明暗表达生效与否）。
    val showValue = !spec.showSwitch || spec.checked
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(enabled = spec.enabled) { spec.onValueClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            spec.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (spec.checked) MaterialTheme.colorScheme.onSurface else dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.weight(1f))
        if (showValue) {
            Text(
                spec.valueText,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = if (spec.checked && spec.style == SceneValueStyle.CUSTOM)
                        FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (spec.showSwitch) {
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = spec.checked,
                onCheckedChange = spec.onSwitchChange,
                enabled = spec.enabled,
            )
        }
    }
}

/**
 * 折叠摘要行（文档 §8.8）：`名称（左） + 摘要（右） + 箭头`，用于「界面 · N 个 ▸」
 * 这类高级入口，**默认一律折叠**。
 */
@Composable
fun CollapsibleSummaryRow(
    title: String,
    summary: String,
    expanded: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .clickable(enabled = enabled) { onToggle() }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))
            if (summary.isNotEmpty()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 小号圆角标签（文档 §8.5）：高 22dp、圆角 6dp、文字 11sp。 */
@Composable
fun TagPill(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Surface(color = container, shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
