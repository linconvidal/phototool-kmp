package br.com.lincon.phototool.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.*

/** Host accessibility preferences; presentation stays in the shared Compose scene. */
@Immutable
data class PhotoPanelPreferences(
    val reduceMotion: Boolean = false,
    val reduceTransparency: Boolean = false,
    val highContrast: Boolean = false,
)

val LocalPhotoPanelPreferences = staticCompositionLocalOf { PhotoPanelPreferences() }

/** Retains its contents through exit; retargeting the spring never starts at a stale endpoint. */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun PhotoDetailPanel(
    visible: Boolean,
    fromTop: Boolean,
    title: String,
    haze: HazeState,
    maximumHeight: Dp,
    close: () -> Unit,
    onHidden: () -> Unit,
    dragDistance: Float? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val preferences = LocalPhotoPanelPreferences.current
    val density = LocalDensity.current
    var panelHeight by remember { mutableFloatStateOf(0f) }
    val travel = (panelHeight.takeIf { it > 0f } ?: with(density) { maximumHeight.toPx() }) + with(density) { 16.dp.toPx() }
    val target = dragDistance?.let { (kotlin.math.abs(it) / travel).coerceIn(0f, 1f) } ?: if (visible) 1f else 0f
    val transition = updateTransition(target, label = "Photo panel visibility")
    val animatedProgress by transition.animateFloat(
        transitionSpec = {
            if (dragDistance != null) snap()
            else if (preferences.reduceMotion) tween(120)
            else spring(dampingRatio = 1f, stiffness = 1400f, visibilityThreshold = .002f)
        },
        label = "Photo panel presentation",
    ) { it }
    // The finger owns presentation while held; only the remaining distance settles on release.
    val progress = if (dragDistance != null) target else animatedProgress
    val mounted = dragDistance != null || transition.currentState > 0f || transition.targetState > 0f
    val currentOnHidden by rememberUpdatedState(onHidden)
    LaunchedEffect(mounted) { if (!mounted) currentOnHidden() }
    if (!mounted) return

    val shape = RoundedCornerShape(24.dp)
    val material = Color(0xff201b17)
    val solid = preferences.reduceTransparency || preferences.highContrast
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().clipToBounds().onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false
    }) {
        Box(
            Modifier.matchParentSize().graphicsLayer { alpha = progress }
                .background(Color.Black.copy(alpha = .22f))
                .clickable(interactionSource = interaction, indication = null, onClick = close)
                .semantics { contentDescription = "Fechar painel da fotografia" },
        )
        Column(
            Modifier.align(if (fromTop) Alignment.TopCenter else Alignment.BottomCenter)
                .padding(8.dp).widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maximumHeight)
                .onSizeChanged { panelHeight = it.height.toFloat() }
                .graphicsLayer {
                    val p = progress.coerceIn(0f, 1f)
                    alpha = if (preferences.reduceMotion) p else (p * 3f).coerceAtMost(1f)
                    translationY = if (preferences.reduceMotion) 0f else
                        (if (fromTop) -1f else 1f) * (size.height + 16.dp.toPx()) * (1f - p)
                    scaleX = if (preferences.reduceMotion) 1f else .985f + .015f * p
                    scaleY = scaleX
                }
                .shadow(24.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = .2f), spotColor = Color.Black.copy(alpha = .3f))
                .clip(shape)
                .hazeEffect(haze) {
                    blurEnabled = !solid
                    backgroundColor = Color(0xff070504)
                    // Keep the GPU blur kernel stable while the layer moves. Rebuilding it
                    // per animation frame stalls first-use rendering on Wasm/WebKit.
                    blurRadius = 28.dp
                    noiseFactor = .025f
                    tints = listOf(HazeTint(material.copy(alpha = .56f)))
                    fallbackTint = HazeTint(material)
                    inputScale = HazeInputScale.Auto
                }
                .border(
                    1.dp,
                    Brush.verticalGradient(listOf(
                        Color.White.copy(alpha = if (preferences.highContrast) .7f else .2f),
                        Color.White.copy(alpha = if (preferences.highContrast) .7f else .055f),
                    )), shape,
                )
                .pointerInput(Unit) { detectTapGestures {} }
                .semantics {
                    paneTitle = title
                    contentDescription = "Painel flutuante: $title"
                    isTraversalGroup = true
                },
            content = content,
        )
    }
}
