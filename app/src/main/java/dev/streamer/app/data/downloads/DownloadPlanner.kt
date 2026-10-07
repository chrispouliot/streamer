package dev.streamer.app.data.downloads

/** State of one song's download (its original file). */
enum class SongDownloadState { Queued, Downloading, Paused, Completed, Failed, Removing }

data class SongDownload(val state: SongDownloadState, val percent: Float?, val bytes: Long)

/** Download state of a downloaded album or playlist. */
data class CollectionDownloadStatus(
    /** Distinct songs currently in the collection. */
    val total: Int,
    val completed: Int,
    val inProgress: Int,
    val failed: Int,
    /** Downloads are waiting for an allowed network (e.g. Wi-Fi). */
    val waitingForNetwork: Boolean,
    /** The kept songs don't match the collection's current songs on the server. */
    val outOfDate: Boolean,
    val keepUpdated: Boolean,
    /** Stopped partway by the user: finished songs kept, nothing more added until resumed. */
    val stopped: Boolean = false,
) {
    val isComplete: Boolean get() = !outOfDate && !stopped && completed == total && total > 0
}

/**
 * Pure decisions about which songs a collection should keep. The old set is
 * kept until every wanted song has finished downloading, then songs that left
 * the collection are released (deleted only if nothing else keeps them).
 */
object DownloadPlanner {
    data class Plan(val toAdd: List<String>, val toRelease: List<String>)

    /**
     * @param entries the collection's current songs in order (may repeat)
     * @param kept songs this collection currently keeps
     * @param completed songs whose download is complete
     */
    fun plan(entries: List<String>, kept: Set<String>, completed: Set<String>): Plan {
        val wanted = LinkedHashSet(entries)
        val toAdd = wanted.filter { it !in kept }
        val allWantedDone = wanted.all { it in completed }
        val toRelease = if (allWantedDone) kept.filter { it !in wanted } else emptyList()
        return Plan(toAdd, toRelease)
    }

    /** Only the release step, for collections that must not add songs automatically. */
    fun releaseOnly(entries: List<String>, kept: Set<String>, completed: Set<String>): List<String> {
        val wanted = entries.toSet()
        return if (wanted.all { it in kept && it in completed }) kept.filter { it !in wanted } else emptyList()
    }

    fun status(
        entries: List<String>,
        kept: Set<String>,
        downloads: Map<String, SongDownload>,
        waitingForNetwork: Boolean,
        keepUpdated: Boolean,
        stopped: Boolean = false,
    ): CollectionDownloadStatus {
        val wanted = entries.toSet()
        val states = wanted.map { downloads[it]?.state }
        val inProgress = states.count { it == SongDownloadState.Queued || it == SongDownloadState.Downloading || it == SongDownloadState.Paused }
        return CollectionDownloadStatus(
            total = wanted.size,
            completed = states.count { it == SongDownloadState.Completed },
            inProgress = inProgress,
            failed = states.count { it == SongDownloadState.Failed },
            waitingForNetwork = waitingForNetwork && inProgress > 0,
            // A stopped collection keeps a subset on purpose; it's only out of date if it keeps songs that left it.
            outOfDate = if (stopped) !wanted.containsAll(kept) else wanted != kept,
            keepUpdated = keepUpdated,
            stopped = stopped,
        )
    }
}
