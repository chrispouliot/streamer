package dev.streamer.app.data.images

import coil3.map.Mapper
import coil3.request.Options
import dev.streamer.app.data.account.AccountRepository
import dev.streamer.app.data.remote.ApiError
import dev.streamer.app.data.remote.SubsonicClient

/**
 * Cover image to load. Stable and free of credentials, so it is used as the
 * memory/disk cache key; the authenticated URL is built only when fetching.
 */
data class CoverArtRequest(val accountId: String, val coverArtId: String, val sizePx: Int) {
    val cacheKey: String get() = "cover:$accountId:$coverArtId:$sizePx"
}

/** Maps a [CoverArtRequest] to a freshly authenticated getCoverArt URL for the active account. */
class CoverArtMapper(
    private val accounts: AccountRepository,
    private val client: SubsonicClient,
) : Mapper<CoverArtRequest, String> {
    override fun map(data: CoverArtRequest, options: Options): String? {
        val (account, auth) = accounts.currentAuth() ?: return null
        if (account.id != data.accountId) return null
        return try {
            client.authenticatedUrl(auth, "getCoverArt", mapOf("id" to data.coverArtId, "size" to data.sizePx.toString())).toString()
        } catch (e: ApiError) {
            null
        }
    }
}
