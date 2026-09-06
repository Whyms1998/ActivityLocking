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

private val LightColors = lightColorScheme(
    primary = Color(0xFF4355E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E8FF),
    onPrimaryContainer = Color(0xFF101A78),
    secondary = Color(0xFF595E71),
    secondaryContainer = Color(0xFFDEE1F9),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF1A1C23),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C23),
    surfaceVariant = Color(0xFFE6E9F3),
    onSurfaceVariant = Color(0xFF767D92),
    outlineVariant = Color(0xFFE2E5EF),
    errorContainer = Color(0xFFFFDAD6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBCC4FF),
    onPrimary = Color(0xFF10227D),
    primaryContainer = Color(0xFF2C3BA8),
    onPrimaryContainer = Color(0xFFE4E8FF),
    secondary = Color(0xFFC2C5DD),
    background = Color(0xFF10121A),
    onBackground = Color(0xFFE3E4EC),
    surface = Color(0xFF161923),
    onSurface = Color(0xFFE3E4EC),
    surfaceVariant = Color(0xFF232737),
    onSurfaceVariant = Color(0xFF9BA2B8),
    outlineVariant = Color(0xFF2C3040),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
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
