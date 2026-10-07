package dev.streamer.app.playback

import android.util.AtomicFile
import dev.streamer.app.model.Artwork
import dev.streamer.app.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/** The queue as last seen, enough to rebuild it paused after the process restarts (no URLs or tokens). */
@Serializable
data class SavedQueue(
    val accountId: String,
    val items: List<SavedItem>,
    val currentOccurrence: Long? = null,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val source: SavedSource? = null,
)

@Serializable
data class SavedItem(
    val occurrenceId: Long,
    val sourceIndex: Int? = null,
    val songId: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val durationMs: Long? = null,
    val track: Int? = null,
    val coverArtId: String? = null,
    val artSeed: String,
    val explicit: Boolean = false,
) {
    fun toSong(accountId: String) = Song(
        id = songId,
        title = title,
        artist = artist,
        artistId = artistId,
        album = album,
        albumId = albumId,
        duration = durationMs?.milliseconds,
        trackNumber = track,
        artwork = Artwork(coverArtId, artSeed, accountId),
        explicit = explicit,
    )

    companion object {
        fun from(item: QueueItem) = SavedItem(
            occurrenceId = item.occurrenceId,
            sourceIndex = item.sourceIndex,
            songId = item.song.id,
            title = item.song.title,
            artist = item.song.artist,
            artistId = item.song.artistId,
            album = item.song.album,
            albumId = item.song.albumId,
            durationMs = item.song.duration?.inWholeMilliseconds,
            track = item.song.trackNumber,
            coverArtId = item.song.artwork.coverArtId,
            artSeed = item.song.artwork.seed,
            explicit = item.song.explicit,
        )
    }
}

@Serializable
data class SavedSource(val kind: String, val id: String? = null, val title: String) {
    fun toSource(): PlaybackSource = when (kind) {
        "album" -> PlaybackSource.Album(id.orEmpty(), title)
        "playlist" -> PlaybackSource.Playlist(id.orEmpty(), title)
        "artist" -> PlaybackSource.Artist(id.orEmpty(), title)
        else -> PlaybackSource.Songs(title)
    }

    companion object {
        fun from(source: PlaybackSource) = when (source) {
            is PlaybackSource.Album -> SavedSource("album", source.id, source.title)
            is PlaybackSource.Playlist -> SavedSource("playlist", source.id, source.title)
            is PlaybackSource.Artist -> SavedSource("artist", source.id, source.title)
            is PlaybackSource.Songs -> SavedSource("songs", null, source.title)
        }
    }
}

/** Single-file store in app-private storage; excluded from backups by the app's rules. */
class QueueStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun save(queue: SavedQueue) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(json.encodeToString(SavedQueue.serializer(), queue).toByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
        }
    }

    suspend fun load(): SavedQueue? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) null else json.decodeFromString(SavedQueue.serializer(), AtomicFile(file).readFully().decodeToString())
        } catch (e: Exception) {
            null // A corrupt or outdated file just means no restored queue.
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        AtomicFile(file).delete()
    }
}
