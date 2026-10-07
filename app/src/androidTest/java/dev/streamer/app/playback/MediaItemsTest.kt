package dev.streamer.app.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.streamer.app.model.Artwork
import dev.streamer.app.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class MediaItemsTest {
    private val song = Song("s1", "Title", "Artist", "ar1", "Album", "al1", 241.seconds, 3, Artwork("cov1", "al1", "acc"), explicit = true)

    @Test
    fun roundTripKeepsOccurrenceSongAndSourcePosition() {
        val item = song.toMediaItem(occurrenceId = 42, sourceIndex = 7, accountId = "acc")
        val back = item.toQueueItem()!!
        assertEquals(42L, back.occurrenceId)
        assertEquals(7, back.sourceIndex)
        assertEquals(song.copy(favouritedAt = null), back.song)
        assertEquals("acc", item.accountId)
    }

    @Test
    fun noCredentialsOrServerUrlsInTheItem() {
        val item = song.toMediaItem(1, null, "acc").withPlayableUri()!!
        val everything = listOf(item.localConfiguration?.uri, item.requestMetadata.mediaUri, item.mediaMetadata.artworkUri).joinToString()
        assertEquals("streamer://song/acc/s1", item.localConfiguration!!.uri.toString())
        assertFalse(everything.contains("http"))
        assertFalse(everything.contains("t="))
    }

    @Test
    fun foreignItemsAreRejected() {
        val foreign = androidx.media3.common.MediaItem.Builder().setMediaId("x")
            .setRequestMetadata(androidx.media3.common.MediaItem.RequestMetadata.Builder().setMediaUri(android.net.Uri.parse("https://evil.example/a.mp3")).build())
            .build()
        assertNull(foreign.withPlayableUri())
        assertNull(foreign.toQueueItem())
    }
}
