package com.clpstudio.filesmanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clpstudio.filesmanager.data.ThemeMode
import com.clpstudio.filesmanager.ui.glass.GlassConfig
import com.clpstudio.filesmanager.ui.glass.LocalGlass

val BrandBlue = Color(0xFF1E6FF0)
val BrandBlueLight = Color(0xFF3FA0FF)
val BrandYellow = Color(0xFFF6B800)

private val Light = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E6FF),
    onPrimaryContainer = Color(0xFF001A44),
    secondary = Color(0xFF3F5F90),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE7FF),
    onSecondaryContainer = Color(0xFF0E1D36),
    tertiary = Color(0xFF8A6300),
    tertiaryContainer = Color(0xFFFFDF9B),
    background = Color(0xFFF7F9FF),
    surface = Color(0xFFF7F9FF),
    surfaceVariant = Color(0xFFE0E6F3),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F4FC),
    surfaceContainer = Color(0xFFEBEFF8),
    surfaceContainerHigh = Color(0xFFE5E9F3),
    surfaceContainerHighest = Color(0xFFDFE4EE),
    outline = Color(0xFF737B8C),
    outlineVariant = Color(0xFFC3C9D8),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9CC2FF),
    onPrimary = Color(0xFF002E6B),
    primaryContainer = Color(0xFF1E5FCB),
    onPrimaryContainer = Color(0xFFD9E6FF),
    secondary = BrandBlueLight,
    onSecondary = Color(0xFF00315A),
    secondaryContainer = Color(0xFF283B5A),
    onSecondaryContainer = Color(0xFFD7E3FF),
    tertiary = Color(0xFFF8C04A),
    tertiaryContainer = Color(0xFF5E4300),
    background = Color(0xFF0B1020),
    surface = Color(0xFF0B1020),
    surfaceVariant = Color(0xFF3A4253),
    surfaceContainerLowest = Color(0xFF070B17),
    surfaceContainerLow = Color(0xFF121829),
    surfaceContainer = Color(0xFF171E30),
    surfaceContainerHigh = Color(0xFF1F2638),
    surfaceContainerHighest = Color(0xFF293043),
    outline = Color(0xFF8D94A5),
    outlineVariant = Color(0xFF3F4757),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun FilesTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    liquidGlass: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = isDark(mode)
    CompositionLocalProvider(LocalGlass provides GlassConfig(enabled = liquidGlass, dark = dark)) {
        val scheme = if (dark) Dark else Light
        MaterialTheme(colorScheme = scheme, typography = Typography(), shapes = AppShapes) {
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
