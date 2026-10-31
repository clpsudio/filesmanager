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

/** Colourful but soft background so the glass has something to refract. */
@Composable
fun GlassBackground(modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val base = MaterialTheme.colorScheme.background
    if (!g.enabled) {
        Box(modifier.background(base))
        return
    }
    val a = if (g.dark) 0.34f else 0.22f
    Box(
        modifier.drawBehind {
            drawRect(base)
            val w = size.width
            val h = size.height
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF1E6FF0).copy(alpha = a), Color.Transparent), center = Offset(w * 0.05f, h * 0.08f), radius = w * 0.95f),
                radius = w * 0.95f, center = Offset(w * 0.05f, h * 0.08f),
            )
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF3FA0FF).copy(alpha = a * 0.9f), Color.Transparent), center = Offset(w * 1.0f, h * 0.45f), radius = w * 0.85f),
                radius = w * 0.85f, center = Offset(w * 1.0f, h * 0.45f),
            )
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFFF6B800).copy(alpha = a * 0.55f), Color.Transparent), center = Offset(w * 0.15f, h * 0.92f), radius = w * 0.8f),
                radius = w * 0.8f, center = Offset(w * 0.15f, h * 0.92f),
            )
        },
    )
}

/** Glass style for a blurred bar/sheet. */
@Composable
fun glassStyle(): HazeStyle {
    val g = LocalGlass.current
    val surface = MaterialTheme.colorScheme.surface
    return HazeStyle(
        backgroundColor = surface,
        tint = HazeTint(surface.copy(alpha = if (g.dark) 0.55f else 0.58f)),
        blurRadius = 26.dp,
        noiseFactor = 0.04f,
        fallbackTint = HazeTint(surface.copy(alpha = if (g.dark) 0.90f else 0.88f)),
    )
}

private fun glassEdge(dark: Boolean): Brush = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = if (dark) 0.22f else 0.85f), Color.White.copy(alpha = if (dark) 0.04f else 0.25f)),
)

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
    val blurred = if (haze != null) clipped.hazeEffect(state = haze, style = glassStyle()) {
        blurEnabled = blurSupported
    } else clipped.background(surface.copy(alpha = 0.9f))
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
    val fill = if (g.dark) {
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.04f)))
    } else {
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.78f), Color.White.copy(alpha = 0.42f)))
    }
    return this.clip(shape).background(fill).border(1.dp, glassEdge(g.dark), shape)
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
    SideEffect {
        if (!g.enabled || !blurSupported) return@SideEffect
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply { blurBehindRadius = 48 }
        window.setDimAmount(0.25f)
    }
}

@Composable
fun dialogContainerColor(): Color {
    val g = LocalGlass.current
    val s = MaterialTheme.colorScheme.surfaceContainerHigh
    return if (g.enabled) s.copy(alpha = if (g.dark) 0.86f else 0.9f) else s
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
