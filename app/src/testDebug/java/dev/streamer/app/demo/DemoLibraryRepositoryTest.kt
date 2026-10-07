package dev.streamer.app.demo

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoLibraryRepositoryTest {
    @Test
    fun starringAndUnstarringUpdatesStarredIds() = runTest {
        val repo = DemoLibraryRepository()
        val song = DemoCatalog.allSongs.first { it.id !in DemoCatalog.initiallyStarred }
        repo.setSongStarred(song.id, true)
        assertTrue(song.id in repo.starredSongIds.first())
        repo.setSongStarred(song.id, false)
        assertFalse(song.id in repo.starredSongIds.first())
    }
}
