package dev.streamer.app.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPlannerTest {
    private fun done(vararg ids: String) = ids.associateWith { SongDownload(SongDownloadState.Completed, 100f, 1) }

    @Test
    fun firstDownloadAddsEverySongOnceInOrder() {
        val plan = DownloadPlanner.plan(listOf("a", "b", "a", "c"), emptySet(), emptySet())
        assertEquals(listOf("a", "b", "c"), plan.toAdd)
        assertEquals(emptyList<String>(), plan.toRelease)
    }

    @Test
    fun oldSongsAreKeptUntilNewOnesFinish() {
        // Daily rewrite: yesterday's x,y kept; today's a,b wanted.
        val pending = DownloadPlanner.plan(listOf("a", "b"), setOf("x", "y", "a", "b"), completed = setOf("x", "y", "a"))
        assertEquals(emptyList<String>(), pending.toAdd)
        assertEquals(emptyList<String>(), pending.toRelease)

        val finished = DownloadPlanner.plan(listOf("a", "b"), setOf("x", "y", "a", "b"), completed = setOf("x", "y", "a", "b"))
        assertEquals(listOf("x", "y"), finished.toRelease)
    }

    @Test
    fun releaseOnlyNeverAddsAndWaitsForCompletion() {
        assertEquals(emptyList<String>(), DownloadPlanner.releaseOnly(listOf("a", "new"), setOf("a", "old"), setOf("a", "old")))
        assertEquals(listOf("old"), DownloadPlanner.releaseOnly(listOf("a"), setOf("a", "old"), setOf("a", "old")))
    }

    @Test
    fun statusCountsDistinctSongsAndDetectsOutOfDate() {
        val downloads = done("a") + ("b" to SongDownload(SongDownloadState.Downloading, 40f, 10)) +
            ("c" to SongDownload(SongDownloadState.Failed, null, 0))
        val s = DownloadPlanner.status(listOf("a", "b", "c", "a"), setOf("a", "b", "c"), downloads, waitingForNetwork = true, keepUpdated = false)
        assertEquals(3, s.total)
        assertEquals(1, s.completed)
        assertEquals(1, s.inProgress)
        assertEquals(1, s.failed)
        assertTrue(s.waitingForNetwork)
        assertFalse(s.outOfDate)
        assertFalse(s.isComplete)

        val changed = DownloadPlanner.status(listOf("a", "d"), setOf("a", "b"), done("a", "b"), false, keepUpdated = true)
        assertTrue(changed.outOfDate)
        assertFalse(changed.isComplete)
    }

    @Test
    fun completeWhenAllWantedDownloadedAndMatching() {
        assertTrue(DownloadPlanner.status(listOf("a", "b"), setOf("a", "b"), done("a", "b"), false, false).isComplete)
    }
}

class StoppedCollectionTest {
    private fun done(vararg ids: String) = ids.associateWith { SongDownload(SongDownloadState.Completed, 100f, 1) }

    @Test
    fun stoppedCollectionIsPartialNotOutOfDateOrComplete() {
        // Stopped after 2 of 4: the finished songs stay kept.
        val s = DownloadPlanner.status(listOf("a", "b", "c", "d"), setOf("a", "b"), done("a", "b"), false, keepUpdated = true, stopped = true)
        assertTrue(s.stopped)
        assertFalse(s.outOfDate)
        assertFalse(s.isComplete)
        assertEquals(2, s.completed)
        assertEquals(4, s.total)
    }

    @Test
    fun stoppedCollectionKeepingSongsThatLeftIsOutOfDate() {
        val s = DownloadPlanner.status(listOf("a", "b"), setOf("a", "gone"), done("a", "gone"), false, keepUpdated = false, stopped = true)
        assertTrue(s.outOfDate)
    }

    @Test
    fun releaseOnlyNeverReleasesFromAPartialSet() {
        assertEquals(emptyList<String>(), DownloadPlanner.releaseOnly(listOf("a", "b"), setOf("a"), setOf("a")))
    }
}
