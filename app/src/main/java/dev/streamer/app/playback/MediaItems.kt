package dev.streamer.app.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.streamer.app.model.Artwork
import dev.streamer.app.model.Song
import kotlin.time.Duration.Companion.milliseconds

/**
 * Credential-free references used inside the player and media session. They
 * are resolved to authenticated URLs only at the moment data is fetched, so
 * tokens never reach the session, notifications, other controllers or saved state.
 */
object MediaUris {
    const val SONG_SCHEME = "streamer"
    const val ART_SCHEME = "streamer-art"

    fun song(accountId: String, songId: String): Uri =
        Uri.Builder().scheme(SONG_SCHEME).authority("song").appendPath(accountId).appendPath(songId).build()

    fun cover(accountId: String, coverArtId: String): Uri =
        Uri.Builder().scheme(ART_SCHEME).authority("cover").appendPath(accountId).appendPath(coverArtId).build()

    /**
     * Download/cache key for a song's original file, independent of any URL.
     * Only original bytes are ever stored under it.
     */
    fun originalKey(accountId: String, songId: String): String = "orig:$accountId:$songId"

    /** (accountId, id) for one of our URIs of the given scheme, else null. */
    fun parse(uri: Uri, scheme: String): Pair<String, String>? {
        if (uri.scheme != scheme) return null
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        return segments[0] to segments[1]
    }
}

private const val KEY_SONG_ID = "streamer.songId"
private const val KEY_ACCOUNT_ID = "streamer.accountId"
private const val KEY_ARTIST_ID = "streamer.artistId"
private const val KEY_ALBUM_ID = "streamer.albumId"
private const val KEY_COVER_ID = "streamer.coverArtId"
private const val KEY_ART_SEED = "streamer.artSeed"
private const val KEY_DURATION_MS = "streamer.durationMs"
private const val KEY_SOURCE_INDEX = "streamer.sourceIndex"
private const val KEY_EXPLICIT = "streamer.explicit"

/**
 * One queue occurrence as a Media3 item. The media ID is the occurrence ID, so
 * duplicate songs stay distinct; the playable URI travels as request metadata
 * (controllers can't send local URIs) and the session restores it.
 */
fun Song.toMediaItem(occurrenceId: Long, sourceIndex: Int?, accountId: String): MediaItem {
    val extras = Bundle().apply {
        putString(KEY_SONG_ID, id)
        putString(KEY_ACCOUNT_ID, accountId)
        putString(KEY_ARTIST_ID, artistId)
        putString(KEY_ALBUM_ID, albumId)
        putString(KEY_COVER_ID, artwork.coverArtId)
        putString(KEY_ART_SEED, artwork.seed)
        putLong(KEY_DURATION_MS, duration?.inWholeMilliseconds ?: -1)
        putInt(KEY_SOURCE_INDEX, sourceIndex ?: -1)
        putBoolean(KEY_EXPLICIT, explicit)
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setTrackNumber(trackNumber)
        .setDurationMs(duration?.inWholeMilliseconds)
        .setArtworkUri(artwork.coverArtId?.let { MediaUris.cover(accountId, it) })
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(extras)
        .build()
    return MediaItem.Builder()
        .setMediaId(occurrenceId.toString())
        .setMediaMetadata(metadata)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(MediaUris.song(accountId, id)).build())
        .build()
}

/** Back from a Media3 item; null for items this app didn't create. */
fun MediaItem.toQueueItem(): QueueItem? {
    val occurrenceId = mediaId.toLongOrNull() ?: return null
    val extras = mediaMetadata.extras ?: return null
    val songId = extras.getString(KEY_SONG_ID) ?: return null
    val accountId = extras.getString(KEY_ACCOUNT_ID)
    val song = Song(
        id = songId,
        title = mediaMetadata.title?.toString() ?: "Untitled",
        artist = mediaMetadata.artist?.toString() ?: "Unknown artist",
        artistId = extras.getString(KEY_ARTIST_ID),
        album = mediaMetadata.albumTitle?.toString(),
        albumId = extras.getString(KEY_ALBUM_ID),
        duration = extras.getLong(KEY_DURATION_MS, -1).takeIf { it >= 0 }?.milliseconds,
        trackNumber = mediaMetadata.trackNumber,
        artwork = Artwork(extras.getString(KEY_COVER_ID), extras.getString(KEY_ART_SEED) ?: songId, accountId),
        explicit = extras.getBoolean(KEY_EXPLICIT),
    )
    return QueueItem(occurrenceId, song, extras.getInt(KEY_SOURCE_INDEX, -1).takeIf { it >= 0 })
}

/** The account a queue item streams from. */
val MediaItem.accountId: String? get() = mediaMetadata.extras?.getString(KEY_ACCOUNT_ID)

/** Restores the playable URI on items received from a controller; drops anything else. */
fun MediaItem.withPlayableUri(): MediaItem? {
    val uri = requestMetadata.mediaUri ?: return null
    if (MediaUris.parse(uri, MediaUris.SONG_SCHEME) == null) return null
    return buildUpon().setUri(uri).build()
}
