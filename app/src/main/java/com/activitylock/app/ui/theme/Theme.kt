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

// 设计令牌：石板蓝灰 + 纸感面 + 关卡状态色（见 docs/UI重构方案.md）
// 状态色：拦截/超额=砖红 #C0392B，通过/允许=苔绿 #2E7D5B，等待/限额=琥珀 #B07D2B
private val LightColors = lightColorScheme(
    primary = Color(0xFF2C3644),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE3EC),
    onPrimaryContainer = Color(0xFF1A222C),
    secondary = Color(0xFF5C6572),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E7EB),
    onSecondaryContainer = Color(0xFF2A2E35),
    tertiary = Color(0xFFB07D2B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF5EAD5),
    onTertiaryContainer = Color(0xFF4A3A18),
    background = Color(0xFFFAFAF7),
    onBackground = Color(0xFF2A2E35),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF2A2E35),
    surfaceVariant = Color(0xFFEFEDE8),
    onSurfaceVariant = Color(0xFF7A8087),
    outlineVariant = Color(0xFFDDDAD2),
    error = Color(0xFFC0392B),
    errorContainer = Color(0xFFF5DAD4),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFF6B1F14),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC6CFDC),
    onPrimary = Color(0xFF1F2733),
    primaryContainer = Color(0xFF38424F),
    onPrimaryContainer = Color(0xFFDDE3EC),
    secondary = Color(0xFF9BA3AF),
    onSecondary = Color(0xFF1F2733),
    secondaryContainer = Color(0xFF323A45),
    onSecondaryContainer = Color(0xFFDDE3EC),
    tertiary = Color(0xFFD8B15E),
    onTertiary = Color(0xFF3A2C10),
    tertiaryContainer = Color(0xFF4A3A18),
    onTertiaryContainer = Color(0xFFF5EAD5),
    background = Color(0xFF161A20),
    onBackground = Color(0xFFE2E4E8),
    surface = Color(0xFF1B2027),
    onSurface = Color(0xFFE2E4E8),
    surfaceVariant = Color(0xFF262C35),
    onSurfaceVariant = Color(0xFF99A0AB),
    outlineVariant = Color(0xFF333A44),
    error = Color(0xFFE08573),
    errorContainer = Color(0xFF5C231A),
    onError = Color(0xFF3A100A),
    onErrorContainer = Color(0xFFF5DAD4),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

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
