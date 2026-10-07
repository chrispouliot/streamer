package dev.streamer.app.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.streamer.app.data.images.CoverArtRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import java.io.IOException

/**
 * Loads notification/lock-screen artwork for `streamer-art://` URIs through the
 * app's image loader (and its cache), so the session only ever exposes
 * credential-free artwork URIs while still supplying the bitmap.
 */
@UnstableApi
class CoverBitmapLoader(private val context: Context, private val scope: CoroutineScope) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = Util.isBitmapFactorySupportedMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: return Futures.immediateFailedFuture(IOException("Undecodable artwork"))
        return Futures.immediateFuture(bitmap)
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val (accountId, coverId) = MediaUris.parse(uri, MediaUris.ART_SCHEME)
            ?: return Futures.immediateFailedFuture(IOException("Unsupported artwork URI"))
        return scope.future {
            val data = CoverArtRequest(accountId, coverId, ART_PX)
            val request = ImageRequest.Builder(context)
                .data(data)
                .memoryCacheKey(data.cacheKey)
                .diskCacheKey(data.cacheKey)
                .allowHardware(false) // The platform session needs a software bitmap.
                .build()
            val result = SingletonImageLoader.get(context).execute(request)
            (result as? SuccessResult)?.image?.toBitmap() ?: throw IOException("Artwork unavailable")
        }
    }

    private companion object {
        const val ART_PX = 512
    }
}
