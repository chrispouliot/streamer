package dev.streamer.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import dev.streamer.app.LocalAppContainer
import dev.streamer.app.model.Artwork

/** Colours for generated placeholder artwork, derived deterministically from a seed. */
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
 * Background tint for player surfaces and detail headers: the cover's main
 * colour (see ArtworkPalette), or the placeholder colour when there is no
 * server artwork. Hue is kept and lightness set for the theme so default text
 * stays readable; changes animate.
 */
@Composable
fun playerTint(artwork: Artwork?): Color {
    val palette = LocalAppContainer.current.artworkPalette
    val dark = LocalDarkTheme.current
    val neutral = MaterialTheme.colorScheme.surfaceContainer
    val placeholder = artwork?.takeIf { it.coverArtId == null }?.let { artworkColors(it).base.toArgb() }
    val main by produceState(initialValue = artwork?.let { palette.cached(it) } ?: placeholder, artwork) {
        value = artwork?.let { palette.mainColor(it) } ?: artwork?.let { artworkColors(it).base.toArgb() }
    }
    val target = main?.let { themeTint(it, dark) } ?: neutral
    val animated by animateColorAsState(target, tween(400), label = "playerTint")
    return animated
}

/** Same hue, saturation capped, lightness fixed for the theme. */
fun themeTint(argb: Int, dark: Boolean): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(argb, hsl)
    val saturation = if (dark) hsl[1].coerceAtMost(0.6f) else hsl[1].coerceAtMost(0.5f)
    return Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], saturation, if (dark) 0.2f else 0.86f)))
}
