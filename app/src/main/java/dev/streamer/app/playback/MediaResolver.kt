package dev.streamer.app.playback

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheKeyFactory
import dev.streamer.app.data.NetworkPolicy
import dev.streamer.app.data.account.AccountRepository
import dev.streamer.app.data.remote.ApiError
import dev.streamer.app.data.remote.SubsonicClient
import java.io.IOException

/** Thrown instead of a network request while offline-only mode is on. */
class OfflineModeException : IOException("Offline mode is on, so only downloaded songs can play.")

/**
 * Turns `streamer://song/<account>/<id>` references into authenticated URLs at
 * the moment data is fetched. [stream] may be transcoded by the server;
 * [original] always returns the original file and is the only source written
 * to (or read through) the download store.
 */
@UnstableApi // DataSpec and cache keys are unstable Media3 APIs.
class MediaResolver(
    private val accounts: AccountRepository,
    private val client: SubsonicClient,
    private val policy: NetworkPolicy,
) {
    fun stream(spec: DataSpec): DataSpec = resolve(spec, "stream")

    fun original(spec: DataSpec): DataSpec = resolve(spec, "download")

    private fun resolve(spec: DataSpec, endpoint: String): DataSpec {
        if (policy.offlineOnly.value) throw OfflineModeException()
        val (accountId, songId) = MediaUris.parse(spec.uri, MediaUris.SONG_SCHEME)
            ?: throw IOException("Unsupported media reference")
        val (account, auth) = accounts.currentAuth() ?: throw IOException("Not signed in to your server.")
        if (account.id != accountId) throw IOException("This song belongs to a different server account.")
        val url = try {
            client.authenticatedUrl(auth, endpoint, mapOf("id" to songId))
        } catch (e: ApiError) {
            throw IOException(e.message, e)
        }
        return spec.withUri(url.toString().toUri())
    }

    companion object {
        /** Download-store key for a song reference: its original file, whatever URL is used. */
        val originalKeyFactory = CacheKeyFactory { spec ->
            MediaUris.parse(spec.uri, MediaUris.SONG_SCHEME)?.let { (acc, id) -> MediaUris.originalKey(acc, id) }
                ?: spec.key ?: spec.uri.toString()
        }
    }
}

/**
 * Chooses, per opened item, between a download-backed source (for songs with a
 * download: reads the store, falls back to the original file) and plain
 * streaming, so stream and original bytes are never mixed.
 */
@UnstableApi
class SelectingDataSource(
    private val withDownload: DataSource,
    private val withoutDownload: DataSource,
    private val hasDownload: (android.net.Uri) -> Boolean,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        withDownload.addTransferListener(transferListener)
        withoutDownload.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (hasDownload(dataSpec.uri)) withDownload else withoutDownload
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(active).read(buffer, offset, length)

    override fun getUri(): android.net.Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    class Factory(
        private val withDownload: DataSource.Factory,
        private val withoutDownload: DataSource.Factory,
        private val hasDownload: (android.net.Uri) -> Boolean,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SelectingDataSource(withDownload.createDataSource(), withoutDownload.createDataSource(), hasDownload)
    }
}
