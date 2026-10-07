package dev.streamer.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 8dp-grid spacing and sizing tokens. */
object Dimens {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    val pagePaddingCompact = 16.dp
    val pagePaddingWide = 24.dp
    val minTouchTarget = 48.dp

    val rowArt = 48.dp
    val miniPlayerArt = 44.dp
    val cardWidth = 168.dp
    val playerPaneWidth = 340.dp

    /** Must match the shapes used by the mini-player card and pane (shapes.medium / extraLarge). */
    val miniPlayerCorner = 12.dp
    val paneCorner = 24.dp
}

internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
