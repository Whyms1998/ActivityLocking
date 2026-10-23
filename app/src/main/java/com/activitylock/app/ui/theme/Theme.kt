package com.activitylock.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 设计令牌（唯一事实来源）—— 依据《应用锁 · 完整设计与实施文档》§5~§7。
 *
 * 命名按**用途**而非长相映射到 Material3 语义角色：
 *
 * | 文档令牌 | 浅色 | 深色 | Material3 角色 |
 * |---|---|---|---|
 * | `accent`        | `#2F7DD1` | `#4A90D9` | `primary`            |
 * | `surface`       | `#FFFFFF` | `#121212` | `background` / `surface` |
 * | `surface_card`  | `#F7F7F8` | `#1E1E22` | `surfaceContainerLow` |
 * | `surface_elevated` | `#FFFFFF` | `#26262B` | `surfaceContainerHigh` |
 * | `surface_sunken`| `#F0F0F2` | `#0E0E10` | `surfaceContainerLowest` |
 * | `text_primary`  | `#1A1A1A` | `#E6E6E6` | `onSurface` / `onBackground` |
 * | `text_secondary`| `#8A8A8E` | `#8A8A8E` | `onSurfaceVariant`   |
 * | `divider`       | `#14000000` | `#2E808080` | `outlineVariant`  |
 * | `tag_warn`      | `#C9861F` | `#E8A33D` | `tertiary`           |
 * | `danger`        | `#D9463C` | `#E05A50` | `error`              |
 *
 * ★ 相对 v1.22.0 的两个硬性修正：
 *  1. **强调色由石板蓝灰 `#2C3644` 改为文档规定的蓝 `#2F7DD1` / `#4A90D9`** ——
 *     `primary` 直接驱动「开关开启轨道 / 自定义值文字 / 主按钮 / 选中态」，
 *     所以这一处改动就让全 App 的开关从白色/灰黑统一成蓝色（文档 §5.2 明令）。
 *  2. 补齐 `surfaceContainer*` 四档 —— 文档把「卡片 / 弹层 / 凹陷区」拆成三个
 *     不同亮度，旧版只有 `surface` + `surfaceVariant` 两档，卡片和弹层同色、
 *     层级分不开。
 */
private val LightColors = lightColorScheme(
    // ---- accent ----
    primary = Color(0xFF2F7DD1),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE1EEFB),
    onPrimaryContainer = Color(0xFF16456F),
    // ---- 中性 ----
    secondary = Color(0xFF5F6673),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDEFF2),
    onSecondaryContainer = Color(0xFF2A2E35),
    // ---- tag_warn / banner_warn ----
    tertiary = Color(0xFFC9861F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBEFDA),
    onTertiaryContainer = Color(0xFF6A4A10),
    // ---- surface ----
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF0F0F2),
    onSurfaceVariant = Color(0xFF8A8A8E),
    surfaceContainerLowest = Color(0xFFF0F0F2),
    surfaceContainerLow = Color(0xFFF7F7F8),
    surfaceContainer = Color(0xFFF4F5F6),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE8E9EB),
    // ---- 描边 ----
    outline = Color(0xFFB9BCC2),
    outlineVariant = Color(0xFFE4E5E8),
    // ---- danger ----
    error = Color(0xFFD9463C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE3E1),
    onErrorContainer = Color(0xFF7A1F17),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4A90D9),
    onPrimary = Color(0xFF0E2540),
    primaryContainer = Color(0xFF1E3550),
    onPrimaryContainer = Color(0xFFBBD6F2),
    secondary = Color(0xFF9BA3AF),
    onSecondary = Color(0xFF1A1D22),
    secondaryContainer = Color(0xFF2A2F37),
    onSecondaryContainer = Color(0xFFDCE0E6),
    tertiary = Color(0xFFE8A33D),
    onTertiary = Color(0xFF3A2A0C),
    tertiaryContainer = Color(0xFF4A3A18),
    onTertiaryContainer = Color(0xFFF5EAD5),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF1E1E22),
    onSurfaceVariant = Color(0xFF8A8A8E),
    surfaceContainerLowest = Color(0xFF0E0E10),
    surfaceContainerLow = Color(0xFF1E1E22),
    surfaceContainer = Color(0xFF232327),
    surfaceContainerHigh = Color(0xFF26262B),
    surfaceContainerHighest = Color(0xFF2B2B30),
    outline = Color(0xFF55585E),
    outlineVariant = Color(0xFF2E2E33),
    error = Color(0xFFE05A50),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF4A1E19),
    onErrorContainer = Color(0xFFF3C9C4),
)

/**
 * 圆角令牌（文档 §6.2）：`xs 6 / s 9 / m 12 / l 16 / xl 26`。
 * 页面骨架里组件默认走 `medium`（12dp），与文档「卡片 / 弹层 / 主按钮默认 12dp」一致。
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(9.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

/** 尺寸令牌（文档 §6.1/§6.3/§6.4）。4 的倍数，禁止随手值。 */
object Tokens {
    val pageMargin = 16.dp
    val space1 = 4.dp
    val space2 = 8.dp
    val space3 = 12.dp
    val space4 = 16.dp
    val space5 = 20.dp
    val space6 = 24.dp
    val space8 = 32.dp

    /** 卡片间距（文档 §6.3 `card_gap`）。 */
    val cardGap = 10.dp
    val cardPadding = 14.dp

    /** 行高（文档 §6.4）。 */
    val rowHeight = 56.dp
    val rowHeightCompact = 52.dp
    val rowHeightCard = 68.dp

    /** 最小触控目标（文档 §6.4 硬性要求）。 */
    val touchMin = 48.dp
    val buttonHeight = 48.dp
    val inputHeight = 44.dp

    /** 图标尺寸（文档 §6.5）。 */
    val iconApp = 50.dp
    val iconHeader = 38.dp
    val iconFunc = 40.dp
    val iconSmall = 28.dp
}

/**
 * 语义状态色（文档 §5.1 `tag_success` / `tag_warn` / `tag_muted`）。
 *
 * Material3 的 ColorScheme 没有「成功」这一角色，所以单独在这里暴露 ——
 * 这样可以保证**全 App 只有一个地方**定义状态色，不会再有人往业务代码里写
 * `Color(0xFF2E7D5B)` 这种字面量（文档 §5 明令禁止）。
 */
object AppColors {
    /** 已启用 / 通过 / 允许（苔绿）。 */
    val success: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF4CC38A) else Color(0xFF2E8B57)

    /** 拦截中 / 待恢复 / 等待（琥珀）。 */
    val warn: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFFE8A33D) else Color(0xFFC9861F)

    /** 未导入 / 未启用（中性灰）。 */
    val muted: Color
        @Composable get() = Color(0xFF8A8A8E)

    /** 错误 / 锁定失败（砖红）。 */
    val danger: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFFE05A50) else Color(0xFFD9463C)
}

@Composable
fun ActivityLockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
