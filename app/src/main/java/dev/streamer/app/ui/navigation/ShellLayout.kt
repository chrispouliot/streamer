package dev.streamer.app.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.streamer.app.ui.theme.Dimens

enum class WidthClass { Compact, Medium, Expanded }

/** Material window width breakpoints, applied to the app's actual window width. */
fun widthClassFor(width: Dp): WidthClass = when {
    width < 600.dp -> WidthClass.Compact
    width < 840.dp -> WidthClass.Medium
    else -> WidthClass.Expanded
}

private val RailWidth = 80.dp
private val MinContentBesidePane = 480.dp

data class ShellLayout(
    val widthClass: WidthClass,
    /** True when a persistent player pane fits beside comfortably wide content. */
    val playerPaneFits: Boolean,
) {
    val usesRail: Boolean get() = widthClass != WidthClass.Compact
    val pagePadding: Dp get() = if (widthClass == WidthClass.Compact) Dimens.pagePaddingCompact else Dimens.pagePaddingWide

    companion object {
        fun forWindowWidth(width: Dp): ShellLayout {
            val widthClass = widthClassFor(width)
            val paneFits = widthClass == WidthClass.Expanded &&
                width - RailWidth - Dimens.playerPaneWidth >= MinContentBesidePane
            return ShellLayout(widthClass, paneFits)
        }
    }
}

val LocalShellLayout = staticCompositionLocalOf { ShellLayout(WidthClass.Compact, playerPaneFits = false) }

/**
 * Height of the mini-player floating over the bottom of the content, or 0dp.
 * Scrolling screens add it to their bottom content padding so the last items
 * can scroll clear of the player.
 */
val LocalFloatingPlayerHeight = compositionLocalOf { 0.dp }

/**
 * Status bar and top cutout. The shell leaves this to each page: plain pages
 * are padded by the shell's [TopInset] wrapper, while artwork-tinted detail
 * pages draw their background behind the status bar and pad only content.
 */
val WindowInsets.Companion.topBar: WindowInsets
    @Composable get() = safeDrawing.only(WindowInsetsSides.Top)
