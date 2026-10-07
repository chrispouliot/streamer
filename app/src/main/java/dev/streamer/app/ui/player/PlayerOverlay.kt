package dev.streamer.app.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.playback.PlayerState
import dev.streamer.app.ui.components.Favorites
import dev.streamer.app.ui.navigation.AppNavigator
import dev.streamer.app.ui.navigation.PlayerSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Collapse progress past which a released drag closes the player. */
private const val DismissProgress = 0.2f
private val DismissVelocity = 1000.dp // per second, downward
private val CollapsedCorner = 12.dp

/**
 * Progress of the player sheet between expanded (0) and collapsed into the
 * mini-player (1). Drags write [progress] synchronously; a release or an
 * open/close request starts one settle animation, cancelling any previous one.
 */
@Stable
class SheetMotion(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(1f)
        private set
    private var target = 1f
    private var job: Job? = null

    fun dragBy(delta: Float) {
        job?.cancel()
        progress = (progress + delta).coerceIn(0f, 1f)
    }

    /** [velocity] is in progress units per second. */
    fun settleTo(value: Float, velocity: Float = 0f) {
        if (value == target && job?.isActive == true) return
        target = value
        job?.cancel()
        job = scope.launch {
            animate(
                initialValue = progress,
                targetValue = value,
                initialVelocity = velocity,
                // A fine threshold: the default (0.01) ends the spring a few pixels
                // short and then snaps, which shows as a jump at the handover.
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                    visibilityThreshold = 0.0005f,
                ),
            ) { v, _ -> progress = v }
        }
    }
}

@Composable
fun rememberSheetMotion(): SheetMotion {
    val scope = rememberCoroutineScope()
    return remember(scope) { SheetMotion(scope) }
}

/** What the sheet turns into when collapsed: the mini-player card, or the pane on wide windows. */
class CollapsedTarget(
    val bounds: Rect,
    val color: Color,
    val corner: Dp,
    val content: @Composable () -> Unit,
)

/**
 * Full player sheet that morphs to and from [collapsed]. Its outline moves
 * from the full area to the target's bounds while the background shifts to the
 * target's colour; the full player (scaled uniformly and clipped) fades out as
 * a copy of the collapsed card fades in at its exact position. The real card is
 * hidden by the caller while [SheetMotion.progress] is below 1, so the two never
 * show at once. Not composed while fully collapsed.
 */
@Composable
fun PlayerOverlay(
    open: Boolean,
    sheet: PlayerSheet,
    motion: SheetMotion,
    collapsed: CollapsedTarget?,
    state: PlayerState,
    player: PlayerController,
    favorites: Favorites,
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(open) { motion.settleTo(if (open) 0f else 1f) }
    // Recompose only when this flips; per-frame progress is read in draw/layer blocks.
    val fullyCollapsed by remember(motion) { derivedStateOf { motion.progress >= 1f } }
    if (!open && fullyCollapsed) return

    val density = LocalDensity.current
    val dismissVelocityPx = with(density) { DismissVelocity.toPx() }
    val fallbackHeightPx = with(density) { 72.dp.toPx() }
    val cornerPx = with(density) { (collapsed?.corner ?: 0.dp).toPx() }
    val background = MaterialTheme.colorScheme.background
    val collapsedColor = collapsed?.color ?: background
    var full by remember { mutableStateOf(Rect.Zero) }

    // Without a measured target, collapse to a strip at the bottom edge.
    fun target(): Rect = collapsed?.bounds?.takeIf { !it.isEmpty }
        ?: Rect(full.left, full.bottom - fallbackHeightPx, full.right, full.bottom)
    fun window(p: Float): Rect = lerp(full, target(), p)
    // Finger travel for a full collapse. At least half the height, so a target
    // near the top (the pane on wide windows) does not make drags overly sensitive.
    fun travel(): Float = maxOf(target().top - full.top, full.height / 2f, 1f)

    val dragState = rememberDraggableState { delta -> motion.dragBy(delta / travel()) }
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { full = it.boundsInRoot() }
            .drawBehind {
                if (full.isEmpty) return@drawBehind
                val p = motion.progress
                val r = window(p)
                drawRoundRect(
                    color = lerp(background, collapsedColor, ease(p, 0.2f, 0.9f)),
                    topLeft = Offset(r.left - full.left, r.top - full.top),
                    size = r.size,
                    cornerRadius = CornerRadius(cornerPx * p),
                )
            },
    ) {
        // Copy of the collapsed card, under the full player, positioned at the moving window.
        if (collapsed != null && !collapsed.bounds.isEmpty) {
            val widthPx = collapsed.bounds.width.roundToInt()
            val heightPx = collapsed.bounds.height.roundToInt()
            Box(
                Modifier
                    // Exact pixel size of the real card, avoiding dp rounding at the handover.
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints.fixed(widthPx, heightPx))
                        layout(widthPx, heightPx) { placeable.place(0, 0) }
                    }
                    .graphicsLayer {
                        if (full.isEmpty) {
                            alpha = 0f
                            return@graphicsLayer
                        }
                        val r = window(motion.progress)
                        translationX = r.left - full.left
                        translationY = r.top - full.top
                        alpha = ease(motion.progress, 0.55f, 1f)
                    }
                    .clearAndSetSemantics { },
            ) { collapsed.content() }
        }
        Surface(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (full.isEmpty) {
                        alpha = 0f // Not measured yet; avoid a one-frame flash at full size.
                        return@graphicsLayer
                    }
                    val p = motion.progress
                    val r = window(p)
                    val s = (r.width / full.width).coerceAtLeast(0.01f)
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = s
                    scaleY = s
                    translationX = r.left - full.left
                    translationY = r.top - full.top
                    // Clip in unscaled layer coordinates so the on-screen window is exactly r.
                    shape = WindowShape(height = r.height / s, radius = cornerPx * p / s)
                    clip = p > 0f
                    alpha = 1f - ease(p, 0.3f, 0.75f)
                }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    enabled = open,
                    onDragStopped = { velocityPx ->
                        val velocity = velocityPx / travel()
                        if (motion.progress > DismissProgress || velocityPx > dismissVelocityPx) {
                            motion.settleTo(1f, velocity)
                            navigator.closePlayer()
                        } else {
                            motion.settleTo(0f, velocity)
                        }
                    },
                )
                .semantics {
                    customActions = listOf(CustomAccessibilityAction("Close player") { navigator.closePlayer(); true })
                },
            // Transparent: the morphing background is drawn behind, and the
            // full player screens paint their own backgrounds.
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            when (sheet) {
                PlayerSheet.Player -> NowPlayingScreen(state, player, favorites, navigator)
                PlayerSheet.Queue -> QueueScreen(state, player, favorites, navigator)
            }
        }
    }
}

/** 0 before [start], 1 after [end], smooth in between. */
private fun ease(p: Float, start: Float, end: Float): Float {
    val t = ((p - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Top-anchored rounded window of the given height, in layer coordinates. */
private class WindowShape(private val height: Float, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(0f, 0f, size.width, height.coerceAtMost(size.height), CornerRadius(radius)))
}
