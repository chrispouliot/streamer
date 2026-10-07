package dev.streamer.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.streamer.app.model.Artwork
import dev.streamer.app.ui.theme.artworkColors

/**
 * Cover artwork. Until server artwork loading lands (Phase 2) every cover uses
 * the generated fallback, which remains the placeholder for missing art.
 */
@Composable
fun CoverArt(
    artwork: Artwork,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    contentDescription: String? = null,
) {
    val colors = remember(artwork.seed) { artworkColors(artwork) }
    Box(
        modifier
            .clip(shape)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(colors.highlight, colors.base, colors.shadow),
                        center = Offset(size.width * 0.32f, size.height * 0.3f),
                        radius = size.maxDimension * 1.05f,
                    ),
                )
            },
    )
}

@Composable
fun ArtistArt(artwork: Artwork, modifier: Modifier = Modifier) {
    CoverArt(artwork, modifier, shape = CircleShape)
}
