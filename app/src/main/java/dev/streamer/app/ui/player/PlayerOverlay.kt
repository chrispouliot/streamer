package dev.streamer.app.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.playback.PlayerState
import dev.streamer.app.ui.components.Favorites
import dev.streamer.app.ui.navigation.AppNavigator
import dev.streamer.app.ui.navigation.PlayerSheet
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Collapse progress past which a released drag closes the player. */
private const val DismissProgress = 0.2f
private val DismissVelocity = 1000.dp // per second, downward

/** Soft edge between the morphing top band and the fading rest of the sheet. */
private val BandFade = 48.dp

/** Progress after which the collapsed-card copy is composed (it fades in from 0.55). */
private const val COPY_FROM = 0.5f

/**
 * Progress of the player sheet between expanded (0) and collapsed into the
 * mini-player (1). Drags write [progress] synchronously; a release or an
 * open/close request starts one settle animation, cancelling any previous one.
 */
@Stable
class SheetMotion(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(1f)
        private set

    /**
     * The open player's title row on screen, kept across open/close so both
     * directions morph around it. Only updated while fully open.
     */
    var anchor by mutableStateOf<Rect?>(null)
        private set

    fun reportAnchor(bounds: Rect) {
        if (progress == 0f) anchor = bounds
    }
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

/** Lets the open player report its title row, which the collapse animation morphs into the mini-player. */
val LocalCollapseAnchor = staticCompositionLocalOf<((Rect) -> Unit)?> { null }

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
 * Full player sheet that collapses into [collapsed]. The whole sheet drops and
 * narrows toward the target while staying full height; a band at its top the
 * height of the target morphs into it (colour, corners, and a copy of the
 * collapsed card fading in), and everything below that band fades to
 * transparent. The real card is hidden by the caller while
 * [SheetMotion.progress] is below 1, so the two never show at once. Not
 * composed while fully collapsed.
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
    // Recompose only when these flip; per-frame progress is read in draw/layer blocks.
    val fullyCollapsed by remember(motion) { derivedStateOf { motion.progress >= 1f } }
    val showCopy by remember(motion) { derivedStateOf { motion.progress > COPY_FROM } }
    if (!open && fullyCollapsed) return

    val density = LocalDensity.current
    val dismissVelocityPx = with(density) { DismissVelocity.toPx() }
    val fallbackHeightPx = with(density) { 72.dp.toPx() }
    val fadeLengthPx = with(density) { BandFade.toPx() }
    val cornerPx = with(density) { (collapsed?.corner ?: 0.dp).toPx() }
    val background = MaterialTheme.colorScheme.background
    val collapsedColor = collapsed?.color ?: background
    var full by remember { mutableStateOf(Rect.Zero) }
    val reportAnchor: (Rect) -> Unit = remember(motion) { { r -> motion.reportAnchor(r) } }

    // Without a measured target, collapse to a strip at the bottom edge.
    fun target(): Rect = collapsed?.bounds?.takeIf { !it.isEmpty }
        ?: Rect(full.left, full.bottom - fallbackHeightPx, full.right, full.bottom)

    /**
     * Top of the band that becomes the collapsed card: the title row (which holds
     * the same title and artist), else the middle. A tall target (the pane) keeps
     * the top, since it spans the height anyway.
     */
    fun anchorTop(): Float {
        val t = target()
        if (t.height > full.height / 2f) return full.top
        // The queue view has no title row; it morphs from the middle.
        val a = motion.anchor?.takeIf { !it.isEmpty && sheet == PlayerSheet.Player }
        val top = a?.let { it.center.y - t.height / 2f } ?: (full.center.y - t.height / 2f)
        return top.coerceIn(full.top, full.bottom - t.height)
    }

    /** The band on screen: full width at the anchor, narrowing and moving onto the target. */
    fun band(p: Float): Rect {
        val top = anchorTop()
        return lerp(Rect(full.left, top, full.right, top + target().height), target(), p)
    }

    // Finger travel for a full collapse; a floor keeps short distances from being overly sensitive.
    fun travel(): Float = maxOf(target().top - anchorTop(), full.height * 0.35f, 1f)

    val dragState = rememberDraggableState { delta -> motion.dragBy(delta / travel()) }
    Box(modifier.fillMaxSize().onGloballyPositioned { full = it.boundsInRoot() }) {
        Surface(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (full.isEmpty) {
                        alpha = 0f // Not measured yet; avoid a one-frame flash at full size.
                        return@graphicsLayer
                    }
                    val p = motion.progress
                    val b = band(p)
                    val s = (b.width / full.width).coerceAtLeast(0.01f)
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = s
                    scaleY = s
                    // Place the band's layer position (anchor) at the band's screen position.
                    translationX = b.left - full.left
                    translationY = b.top - full.top - s * (anchorTop() - full.top)
                    shape = RoundedCornerShape((cornerPx * p / s).coerceAtLeast(0f))
                    clip = p > 0f
                    // Needed for the fade mask below.
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    val p = motion.progress
                    if (p <= 0f || full.isEmpty) return@drawWithContent
                    // Keep the band, fade everything above and below it (layer coordinates).
                    val s = (band(p).width / full.width).coerceAtLeast(0.01f)
                    val h = size.height
                    val bandStart = ((anchorTop() - full.top) / h).coerceIn(0f, 1f)
                    val bandEnd = (bandStart + target().height / s / h).coerceIn(bandStart, 1f)
                    // Soft edges that tighten as it lands, so nothing trails the card.
                    val soft = fadeLengthPx * (1f - p) / s / h
                    val edgeAbove = (bandStart - soft).coerceIn(0f, bandStart)
                    val edgeBelow = (bandEnd + soft).coerceIn(bandEnd, 1f)
                    // At the very end the band itself hands over to the collapsed-card copy above it,
                    // whose rounded corners then match the real card exactly.
                    val bandAlpha = 1f - ease(p, 0.85f, 1f)
                    val keep = Color.Black.copy(alpha = bandAlpha)
                    val fade = Color.Black.copy(alpha = minOf(bandAlpha, 1f - ease(p, 0.05f, 0.7f)))
                    drawRect(
                        Brush.verticalGradient(
                            0f to fade, edgeAbove to fade, bandStart to keep, bandEnd to keep, edgeBelow to fade, 1f to fade,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
                .drawBehind {
                    // The sheet's background shifts toward the collapsed card's colour.
                    drawRect(lerp(background, collapsedColor, ease(motion.progress, 0.2f, 0.9f)))
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
            color = Color.Transparent, // Background is drawn above so it can change colour.
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Box(Modifier.graphicsLayer { alpha = 1f - ease(motion.progress, 0.3f, 0.75f) }) {
                CompositionLocalProvider(LocalCollapseAnchor provides reportAnchor) {
                    when (sheet) {
                        PlayerSheet.Player -> NowPlayingScreen(state, player, favorites, navigator)
                        PlayerSheet.Queue -> QueueScreen(state, player, favorites, navigator)
                    }
                }
            }
        }
        // Copy of the collapsed card riding the top band. Drawn on top, and only
        // composed late in the motion so it never intercepts taps on the open player.
        if (showCopy && collapsed != null && !collapsed.bounds.isEmpty) {
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
                        val b = band(motion.progress)
                        translationX = b.left - full.left
                        translationY = b.top - full.top
                        alpha = ease(motion.progress, 0.55f, 1f)
                    }
                    .clearAndSetSemantics { },
            ) { collapsed.content() }
        }
    }
}

/** 0 before [start], 1 after [end], smooth in between. */
private fun ease(p: Float, start: Float, end: Float): Float {
    val t = ((p - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
