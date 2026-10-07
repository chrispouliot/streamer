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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dev.streamer.app.data.Connection
import dev.streamer.app.data.images.CoverArtRequest
import dev.streamer.app.model.Artwork
import dev.streamer.app.ui.navigation.LocalConnection
import dev.streamer.app.ui.theme.artworkColors

/** Requested server image size; one cached image per bucket. */
enum class ArtSize(val px: Int) { Small(160), Medium(480), Large(1000) }

/**
 * Cover artwork: the server image when available, drawn over a generated
 * gradient that also serves as placeholder, offline fallback and missing-art.
 */
@Composable
fun CoverArt(
    artwork: Artwork,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    contentDescription: String? = null,
    size: ArtSize = ArtSize.Medium,
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
                        center = Offset(this.size.width * 0.32f, this.size.height * 0.3f),
                        radius = this.size.maxDimension * 1.05f,
                    ),
                )
            },
    ) {
        val coverId = artwork.coverArtId
        val accountId = artwork.accountId
        if (coverId != null && accountId != null) {
            val context = LocalContext.current
            val offlineMode = LocalConnection.current == Connection.OfflineMode
            val request = remember(accountId, coverId, size, offlineMode) {
                val data = CoverArtRequest(accountId, coverId, size.px)
                ImageRequest.Builder(context)
                    .data(data)
                    .memoryCacheKey(data.cacheKey)
                    .diskCacheKey(data.cacheKey)
                    // Offline mode: cached and saved covers only, no requests.
                    .apply { if (offlineMode) networkCachePolicy(CachePolicy.DISABLED) }
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
fun ArtistArt(artwork: Artwork, modifier: Modifier = Modifier, size: ArtSize = ArtSize.Medium) {
    CoverArt(artwork, modifier, shape = CircleShape, size = size)
}
