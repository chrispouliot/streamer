package dev.streamer.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.streamer.app.model.Artwork

/** Colours derived deterministically from an artwork seed. */
data class ArtworkColors(val highlight: Color, val base: Color, val shadow: Color)

fun artworkColors(artwork: Artwork): ArtworkColors {
    // Stable across runs and processes (String.hashCode is specified).
    val hash = artwork.seed.hashCode()
    val hue = ((hash ushr 1) % 360).toFloat()
    val sat = 0.55f + ((hash ushr 9) % 30) / 100f
    return ArtworkColors(
        highlight = Color.hsl((hue + 12f) % 360f, sat, 0.62f),
        base = Color.hsl(hue, sat, 0.38f),
        shadow = Color.hsl((hue + 340f) % 360f, sat, 0.14f),
    )
}

/**
 * Player background tint: the artwork colour pulled strongly toward the
 * theme surface so default text colours keep their contrast.
 */
@Composable
@ReadOnlyComposable
fun playerTint(artwork: Artwork?): Color {
    val surface = MaterialTheme.colorScheme.surfaceContainer
    if (artwork == null) return surface
    val base = artworkColors(artwork).base
    return if (LocalDarkTheme.current) lerp(Color.Black, base, 0.42f) else lerp(Color.White, base, 0.24f)
}
