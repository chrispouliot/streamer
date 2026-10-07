package dev.streamer.app.data.images

import android.content.Context
import android.util.LruCache
import androidx.core.content.edit
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.streamer.app.model.Artwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The main colour of a cover image, extracted with Palette from the small
 * cached cover (the same image list rows show) and remembered per cover, also
 * across restarts, so player surfaces open in their final colour.
 */
class ArtworkPalette(private val context: Context) {
    private val cache = LruCache<String, Int>(300)

    // Opened now so it has loaded from disk by the time the first screen asks.
    private val saved = context.getSharedPreferences("artwork_colors", Context.MODE_PRIVATE)

    private fun key(artwork: Artwork): String? {
        val account = artwork.accountId ?: return null
        val cover = artwork.coverArtId ?: return null
        return "$account:$cover"
    }

    /** Already-extracted colour (ARGB), without loading any image. */
    fun cached(artwork: Artwork): Int? {
        val key = key(artwork) ?: return null
        cache.get(key)?.let { return it }
        if (!saved.contains(key)) return null
        return saved.getInt(key, 0).also { cache.put(key, it) }
    }

    /** Extracts the colour, loading the cover if needed; null without server artwork or when it can't load. */
    suspend fun mainColor(artwork: Artwork): Int? {
        val key = key(artwork) ?: return null
        cached(artwork)?.let { return it }
        val data = CoverArtRequest(artwork.accountId!!, artwork.coverArtId!!, SAMPLE_PX)
        val request = ImageRequest.Builder(context)
            .data(data)
            .diskCacheKey(data.cacheKey)
            .allowHardware(false) // Palette reads pixels.
            .build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
        val color = withContext(Dispatchers.Default) { pick(Palette.from(bitmap).maximumColorCount(16).generate()) } ?: return null
        cache.put(key, color)
        remember(key, color)
        return color
    }

    private fun remember(key: String, color: Int) {
        // A few bytes per cover; start over if it ever grows large.
        if (saved.all.size > MAX_SAVED) saved.edit { clear() }
        saved.edit { putInt(key, color) }
    }

    /**
     * The dominant colour, unless it is near-black or near-white (common
     * backgrounds) and a vivid colour also covers a fair share of the image.
     */
    private fun pick(palette: Palette): Int? {
        val dominant = palette.dominantSwatch ?: return palette.vibrantSwatch?.rgb ?: palette.mutedSwatch?.rgb
        val lightness = dominant.hsl[2]
        if (lightness < 0.12f || lightness > 0.9f) {
            val vivid = palette.vibrantSwatch ?: palette.darkVibrantSwatch ?: palette.lightVibrantSwatch
            if (vivid != null && vivid.population >= dominant.population / 8) return vivid.rgb
        }
        return dominant.rgb
    }

    private companion object {
        /** Matches the small cover size used by rows, so its disk-cached image is reused. */
        const val SAMPLE_PX = 160
        const val MAX_SAVED = 5_000
    }
}
