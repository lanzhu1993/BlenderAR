package com.blenderar.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = BrandTeal,
    onPrimary = DarkBackground,
    primaryContainer = BrandTealDark,
    onPrimaryContainer = DarkBackground,
    secondary = BrandPurple,
    onSecondary = DarkBackground,
    tertiary = BrandGold,
    onTertiary = DarkBackground,
    background = DarkBackground,
    onBackground = ColorOnBackground,
    surface = DarkSurface,
    onSurface = ColorOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = ColorOnSurfaceVariant,
    outline = DarkOutline,
    error = StatusError,
)

@Composable
fun BlenderARTheme(content: @Composable () -> Unit) {
    // 应用定位为深色科技风格,固定使用深色方案
    MaterialTheme(
        colorScheme = DarkColors,
        typography = BlenderTypography,
        content = content,
    )
}
