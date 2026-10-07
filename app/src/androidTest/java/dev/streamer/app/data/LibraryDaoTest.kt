package dev.streamer.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.streamer.app.data.local.AccountEntity
import dev.streamer.app.data.local.AlbumEntity
import dev.streamer.app.data.local.AppDatabase
import dev.streamer.app.data.local.PlaylistEntity
import dev.streamer.app.data.local.PlaylistEntryEntity
import dev.streamer.app.data.local.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun account(id: String) =
        db.accounts().upsert(AccountEntity(id, "https://$id.example/", "u", false, null, null, false, active = false))

    private fun song(acc: String, id: String, title: String = id) =
        SongEntity(acc, id, title, null, null, null, null, 100, null, null, null, null, false, false, null, null, null)

    private fun playlist(acc: String, id: String, name: String = id) =
        PlaylistEntity(acc, id, name, null, null, null, null, null, null, null, removedRemotely = false)

    @Test
    fun playlistKeepsOrderAndDuplicateEntries() = runTest {
        account("a")
        val songs = listOf(song("a", "s1"), song("a", "s2"))
        val entries = listOf("s1", "s2", "s1").mapIndexed { i, id -> PlaylistEntryEntity("a", "p", i, id) }
        db.library().replacePlaylist("a", playlist("a", "p"), songs, entries)
        assertEquals(listOf("s1", "s2", "s1"), db.library().observePlaylistEntries("a", "p").first().map { it.song.id })

        // A later snapshot replaces membership completely.
        db.library().replacePlaylist("a", playlist("a", "p"), songs, listOf(PlaylistEntryEntity("a", "p", 0, "s2")))
        assertEquals(listOf("s2"), db.library().observePlaylistEntries("a", "p").first().map { it.song.id })
    }

    @Test
    fun sameIdsOnDifferentServersStaySeparate() = runTest {
        account("a")
        account("b")
        db.library().upsertSongs(listOf(song("a", "1", "From A"), song("b", "1", "From B")))
        assertEquals(listOf("From A"), db.library().observeSongs("a").first().map { it.title })
        assertEquals(listOf("From B"), db.library().observeSongs("b").first().map { it.title })
    }

    @Test
    fun deletingAccountCascadesToCachedData() = runTest {
        account("a")
        account("b")
        db.library().upsertSongs(listOf(song("a", "1"), song("b", "1")))
        db.library().upsertAlbums(listOf(AlbumEntity("a", "al", "Album", null, null, null, null, null, null)))
        db.accounts().delete("a")
        assertTrue(db.library().observeSongs("a").first().isEmpty())
        assertTrue(db.library().observeAlbums("a").first().isEmpty())
        assertEquals(1, db.library().observeSongs("b").first().size)
    }

    @Test
    fun playlistsMissingFromListingAreMarkedRemovedNotDeleted() = runTest {
        account("a")
        db.library().replacePlaylists("a", listOf(playlist("a", "p1"), playlist("a", "p2")))
        db.library().replacePlaylists("a", listOf(playlist("a", "p1")))
        assertEquals(listOf("p1"), db.library().observePlaylists("a").first().map { it.id })
        assertTrue(db.library().observePlaylist("a", "p2").first()!!.removedRemotely)
    }

    @Test
    fun favouritesListNewestStarredFirst() = runTest {
        account("a")
        db.library().upsertSongs(
            listOf(
                song("a", "old").copy(starred = true, starredAt = "2025-01-01T00:00:00Z"),
                song("a", "new").copy(starred = true, starredAt = "2026-10-01T00:00:00Z"),
                song("a", "unknown").copy(starred = true, starredAt = null),
            ),
        )
        assertEquals(listOf("new", "old", "unknown"), db.library().observeStarredSongs("a").first().map { it.id })
    }

    @Test
    fun starredReplacementClearsOldStars() = runTest {
        account("a")
        db.library().upsertSongs(listOf(song("a", "1").copy(starred = true), song("a", "2")))
        db.library().replaceStarred("a", listOf(song("a", "2").copy(starred = true)))
        assertEquals(setOf("2"), db.library().observeStarredSongIds("a").first().toSet())
    }
}
