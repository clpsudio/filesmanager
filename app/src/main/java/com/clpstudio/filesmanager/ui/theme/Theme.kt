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

/**
 * Light: clean cool near-white (#F6F8FB), white cards with a #E3E8EF hairline, text #111418 /
 * #5B6472 (both AA on white and on the background). Selected chips/segments = filled soft accent.
 */
private val Light = lightColorScheme(
    primary = Color(0xFF1A64DB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF001A44),
    inversePrimary = Color(0xFF9CC2FF),
    secondary = Color(0xFF3F5F90),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFE0FF),
    onSecondaryContainer = Color(0xFF082A66),
    tertiary = Color(0xFF8A6300),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDF9B),
    onTertiaryContainer = Color(0xFF2B1D00),
    background = Color(0xFFF6F8FB),
    onBackground = Color(0xFF111418),
    surface = Color(0xFFF6F8FB),
    onSurface = Color(0xFF111418),
    surfaceVariant = Color(0xFFE9EDF3),
    onSurfaceVariant = Color(0xFF5B6472),
    surfaceTint = Color(0xFF8A93A3),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE3E8EF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF0F3F7),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE6EAF0),
    inverseSurface = Color(0xFF22262D),
    inverseOnSurface = Color(0xFFF2F4F7),
    outline = Color(0xFF6B7484),
    outlineVariant = Color(0xFFBCC4D0),
)

/**
 * Dark: clean neutral near-black (no blue/brown cast). Cards #171A20, elevated #1F232B,
 * hairlines #2A2F38. Selected chips/nav indicators use secondaryContainer = filled accent.
 */
private val Dark = darkColorScheme(
    primary = Color(0xFF6FA8FF),
    onPrimary = Color(0xFF001C40),
    primaryContainer = Color(0xFF1F5BD0),
    onPrimaryContainer = Color(0xFFE3ECFF),
    inversePrimary = BrandBlue,
    secondary = Color(0xFF8FB8FF),
    onSecondary = Color(0xFF00234F),
    secondaryContainer = Color(0xFF2B58B0),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFFFC94A),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF4A3A10),
    onTertiaryContainer = Color(0xFFFFE6A8),
    background = Color(0xFF0D0F13),
    onBackground = Color(0xFFF2F4F7),
    // "surface" = the card colour, so components that fill with surface (segmented buttons,
    // top app bars, menus) sit on cards instead of punching near-black holes into them.
    surface = Color(0xFF171A20),
    onSurface = Color(0xFFF2F4F7),
    surfaceVariant = Color(0xFF1F232B),
    onSurfaceVariant = Color(0xFFA3ABB8),
    surfaceTint = Color(0xFF9AA3B2),
    surfaceBright = Color(0xFF2A2F38),
    surfaceDim = Color(0xFF0D0F13),
    surfaceContainerLowest = Color(0xFF08090C),
    surfaceContainerLow = Color(0xFF13161B),
    surfaceContainer = Color(0xFF171A20),
    surfaceContainerHigh = Color(0xFF1F232B),
    surfaceContainerHighest = Color(0xFF272B34),
    inverseSurface = Color(0xFFE6E9EF),
    inverseOnSurface = Color(0xFF16181D),
    outline = Color(0xFF5E6675),
    outlineVariant = Color(0xFF3A404C),
    scrim = Color(0xFF000000),
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
