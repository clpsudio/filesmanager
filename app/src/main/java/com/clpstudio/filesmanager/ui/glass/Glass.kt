package com.clpstudio.filesmanager.ui.glass

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Liquid glass on/off (Settings > Appearance) and the current light/dark. */
data class GlassConfig(val enabled: Boolean, val dark: Boolean)

val LocalGlass = staticCompositionLocalOf { GlassConfig(enabled = true, dark = false) }

/** The blur source of the current screen (only bars and sheets blur, never list items). */
val LocalHaze = compositionLocalOf<HazeState?> { null }

/** Real blur from Android 12; Android 8-11 get a frosted tint with the same shapes. */
val blurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Screen background. Light: near-white with a faint cool blue wash. Dark: clean neutral
 * near-black with only a barely-there cool highlight at the top (no colour blobs, no warm tint).
 */
@Composable
fun GlassBackground(modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val base = MaterialTheme.colorScheme.background
    if (!g.enabled) {
        Box(modifier.background(base))
        return
    }
    Box(
        modifier.drawBehind {
            drawRect(base)
            val w = size.width
            val h = size.height
            if (g.dark) {
                drawRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.035f), Color.Transparent), startY = 0f, endY = h * 0.45f),
                )
            } else {
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFF1E6FF0).copy(alpha = 0.10f), Color.Transparent), center = Offset(w * 0.05f, h * 0.04f), radius = w * 0.95f),
                    radius = w * 0.95f, center = Offset(w * 0.05f, h * 0.04f),
                )
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFF3FA0FF).copy(alpha = 0.08f), Color.Transparent), center = Offset(w * 1.0f, h * 0.5f), radius = w * 0.85f),
                    radius = w * 0.85f, center = Offset(w * 1.0f, h * 0.5f),
                )
            }
        },
    )
}

/** Glass style for a blurred bar/sheet. */
@Composable
fun glassStyle(): HazeStyle = glassStyle(elevated = false)

@Composable
private fun glassStyle(elevated: Boolean): HazeStyle {
    val g = LocalGlass.current
    val surface = MaterialTheme.colorScheme.surface
    if (g.dark) {
        // Neutral, cool and subtle: a dark veil (bars) or the elevated card colour (pills).
        val veil = if (elevated) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background
        return HazeStyle(
            backgroundColor = surface,
            tint = HazeTint(veil.copy(alpha = if (elevated) 0.86f else 0.80f)),
            blurRadius = 24.dp,
            noiseFactor = 0.02f,
            fallbackTint = HazeTint(veil.copy(alpha = 0.96f)),
        )
    }
    return HazeStyle(
        backgroundColor = surface,
        tint = HazeTint(surface.copy(alpha = if (elevated) 0.82f else 0.62f)),
        blurRadius = 26.dp,
        noiseFactor = 0.03f,
        fallbackTint = HazeTint(surface.copy(alpha = 0.92f)),
    )
}

private fun glassEdge(dark: Boolean): Brush = if (dark) {
    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.05f)))
} else {
    Brush.verticalGradient(listOf(Color(0xFFE3E8EF), Color(0xFFDCE2EA)))
}

/**
 * A blurred glass bar (top bar, bottom bar, search bar). Flat surface colour when glass is off.
 */
@Composable
fun Modifier.glassBar(shape: Shape? = null): Modifier {
    val g = LocalGlass.current
    val haze = LocalHaze.current
    val surface = MaterialTheme.colorScheme.surface
    if (!g.enabled) return this.then(if (shape != null) Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainer) else Modifier.background(surface))
    val clipped = if (shape != null) this.clip(shape) else this
    val elevated = shape != null
    val blurred = if (haze != null) clipped.hazeEffect(state = haze, style = glassStyle(elevated)) {
        blurEnabled = blurSupported
    } else clipped.background(
        when {
            g.dark && elevated -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)
            g.dark -> MaterialTheme.colorScheme.background.copy(alpha = 0.94f)
            else -> surface.copy(alpha = if (elevated) 0.96f else 0.92f)
        },
    )
    return if (shape != null) blurred.border(1.dp, glassEdge(g.dark), shape) else blurred
}

/**
 * A glass panel/tile (no blur, so it is cheap to use in grids): translucent tint, specular
 * gradient and a light edge. A plain Material card colour when glass is off.
 */
@Composable
fun Modifier.glassPanel(shape: Shape = RoundedCornerShape(24.dp)): Modifier {
    val g = LocalGlass.current
    if (!g.enabled) return this.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow)
    if (g.dark) {
        // Solid neutral card (#171A20) with a faint cool sheen (white ~6% at the top) and a 1dp hairline.
        val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.015f)))
        val edge = Brush.verticalGradient(listOf(Color(0xFF353B46), Color(0xFF2A2F38)))
        return this.clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).background(sheen).border(1.dp, edge, shape)
    }
    // Light: white card, 1dp #E3E8EF hairline and a soft cool shadow.
    return this.shadow(3.dp, shape, clip = false, ambientColor = Color(0x1A1B2A44), spotColor = Color(0x261B2A44))
        .clip(shape).background(Color.White).border(1.dp, Color(0xFFE3E8EF), shape)
}

/** Springy press feedback. */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.95f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) pressed else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Dialog windows: blur what is behind them on Android 12+ when glass is on. */
@Composable
fun DialogGlassEffect() {
    val g = LocalGlass.current
    val view = LocalView.current
    // Applied once per dialog window (setting window attributes on every recomposition makes
    // the window relayout again and again).
    androidx.compose.runtime.LaunchedEffect(view, g.enabled) {
        // Dialogs are solid now (round 5): a see-through dialog let the page behind show through the text
        // fields and rows (Samsung often skips the blur). Normal dim behind, no blur.
        if (!g.enabled || !blurSupported) return@LaunchedEffect
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
    }
}

@Composable
fun dialogContainerColor(): Color {
    // Always opaque: dialogs, sheets and menus never show what is behind them.
    return MaterialTheme.colorScheme.surfaceContainerHigh
}

/**
 * Screen scaffold: background (glass gradient or plain), a blur source for the bars, and content
 * that scrolls under the glass top/bottom bars. Lists must use the given padding as contentPadding.
 */
@Composable
fun GlassScaffold(
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: SnackbarHostState? = null,
    content: @Composable BoxScope.(PaddingValues) -> Unit,
) {
    val haze = rememberHazeState(blurEnabled = blurSupported)
    CompositionLocalProvider(LocalHaze provides haze) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
                topBar = topBar,
                bottomBar = bottomBar,
                floatingActionButton = floatingActionButton,
                snackbarHost = { if (snackbarHost != null) SnackbarHost(snackbarHost) },
            ) { padding ->
                Box(Modifier.fillMaxSize().hazeSource(haze)) {
                    GlassBackground(Modifier.fillMaxSize())
                    content(padding)
                }
            }
        }
    }
}

val GlassCorner: Dp = 24.dp
